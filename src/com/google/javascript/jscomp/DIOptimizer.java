package com.google.javascript.jscomp;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.javascript.jscomp.DIOptimizerInfo.BindingInfo;
import com.google.javascript.jscomp.DIOptimizerInfo.ConstructorInfo;
import com.google.javascript.jscomp.DIOptimizerInfo.InjectorInfo;
import com.google.javascript.jscomp.DIOptimizerInfo.InterceptorInfo;
import com.google.javascript.jscomp.DIOptimizerInfo.MethodInjectionInfo;
import com.google.javascript.jscomp.DIOptimizerInfo.ModuleInfo;
import com.google.javascript.jscomp.DIOptimizerInfo.ModuleInitializerInfo;
import com.google.javascript.jscomp.DIOptimizerInfo.PrototypeInfo;
import com.google.javascript.jscomp.DIConsts.ClassMatchType;
import com.google.javascript.rhino.IR;
import com.google.javascript.rhino.JSDocInfo;
import com.google.javascript.rhino.JSDocInfoBuilder;
import com.google.javascript.rhino.JSTypeExpression;
import com.google.javascript.rhino.Node;

/**
 * @author Taketoshi Aono
 * 
 *         Rewrite all DI. Rewrite process is like follows.
 * 
 *         <code>
 *  //Modules.
 *  FooModule.prototype.configure = function(binder) {
 *    //before
 *    binder.bind('foo', FooClass);
 *    binder.bindProvider("bar", BarClass, function(foo) {
 *      return new BarClass(foo);
 *    });
 *    binder.bindInterceptor(
 *      camp.injections.Matcher.inNamespace("foo.bar.baz"),
 *      camp.injections.Matcher.like("do*"),
 *      camp.injections.Matcher.PointCuts.BEFORE,
 *      function(methodInvocation) {
 *        console.log("log call before : " + joinPoint.getQualifiedName());
 *        return methodInvocation.proceed();
 *      }
 *    );
 *    
 *    //after
 *    //foo binding is inlined by compiler.
 *    this.bar = function() {
 *      return new BarClass(new FooClass);
 *    }
 *    this.jscomp$interceptor$0 = function(jscomp$methodInvocation$this,
 *                                         jscomp$methodInvocation$arguments,
 *                                         jscomp$methodInvocation$constructorName,
 *                                         jscomp$methodInvocation$methodName,
 *                                         jscomp$methodInvocation$proceed) {
 *      console.log("log call before : " + jscomp$methodInvocation$constructorName + ("." + jscomp$joinPoint$methodName));
 *      return jscomp$methodInvocation$proceed.apply(this, jscomp$methodInvocation$arguments);
 *    }
 *  }
 *  
 *  //Module initializers.
 *  //before
 *  camp.injections.modules.init([FooModule], function(injector) {
 *    var barClass = injector.createInstance(BarClass);
 *    //BazClass's constructor is like follows
 *    //function BazClass(foo) {...
 *    //BazClass's method named 'doSomething' is like follows
 *    //BazClass.prototype.doSomething = function() {
 *    //  return 1;
 *    //}
 *    var bazClass = injector.createInstance(bazClass);
 *  });
 *  
 *  //after
 *  var fooModule = new FooModule;
 *  fooModule.configure();
 *  var barClass = fooModule.bar();
 *  var bazClass = new BazClass(new FooClass, fooModule.jscomp$interceptor$0);
 *  //Now the BazClass's constructor is rewrited as follows
 *  //function BazClass(foo, jscomp$interceptor$0) {
 *  //  this.jscomp$interceptor$0 = jscomp$interceptor$0;
 *  //  ...
 *  //And the BazClass's method 'doSomething' is rewrited like follows.
 *  //BazClass.prototype.doSomething = function() {
 *  //  this.jscomp$interceptor$0 && this.jscomp$interceptor(this, arguments, "BazClass", "doSomething");
 * </code>
 * 
 */
final class DIOptimizer {

  private final DIOptimizerInfo diInfo;

  private final AbstractCompiler compiler;

  private final CodingConvention convention;

  private int interceptorId = 0;


  public DIOptimizer(AbstractCompiler compiler,
      DIOptimizerInfo dIOptimizerInfo) {
    this.compiler = compiler;
    this.convention = compiler.getCodingConvention();
    this.diInfo = dIOptimizerInfo;
  }


  public void optimize() {
    for (ModuleInfo moduleInfo : diInfo.getModuleInfoMap().values()) {
      rewriteBinding(moduleInfo);
    }
    for (ModuleInitializerInfo moduleInitInfo : diInfo.getModuleInitInfoList()) {
      rewriteInjection(moduleInitInfo);
    }
  }


  /**
   * The interface of rewriters.
   */
  private interface NodeRewriter {
    public void rewrite();
  }


  /**
   * Insert a static factory method.
   * 
   * @example <code>
   * function Foo(a,b,c) {
   * }
   * Foo.jscomp$newInstance = function(bindings) {
   *   return new Foo(bindings.getA(), bindings.getB(), bindings.getC());
   * }
   * </code>
   */
  private final class FactoryInjector {

    private static final String INSTANCE_NAME = "instance";

    private ConstructorInfo constructorInfo;


    public FactoryInjector(ConstructorInfo constructorInfo) {
      this.constructorInfo = constructorInfo;
    }


    public void process() {

      // If constructor is already has factory method,
      // skip processes.
      if (constructorInfo.hasInstanceFactory()) {
        return;
      }

      constructorInfo.setHasInstanceFactory();

      Node constructorNode;

      // If this constructor definition is aliased,
      // the factory method injection is inserted after assignment node.
      if (constructorInfo.getAliasPoint() == null) {
        constructorNode = constructorInfo.getConstructorNode();
      } else {
        constructorNode = constructorInfo.getAliasPoint();
      }

      if (constructorNode != null) {
        insertFactory(constructorNode);
      }
    }

    
    /**
     * Insert a factory method to the node trees. 
     */
    private void insertFactory(Node constructorNode) {
      Node stmtBeginning =
          DIProcessor.getStatementBeginningNode(constructorNode);
      Preconditions.checkNotNull(stmtBeginning);

      String constructorName = constructorInfo.getConstructorName();

      Node block = IR.block();
      Node assign = createFactoryAssignmentNode(constructorName, block);
      Node newCall = IR.newNode(NodeUtil.newQualifiedNameNode(convention, constructorName));

      addParameters(newCall, constructorInfo.getParamList());
      addMethodInjections(block, newCall);

      Node expr = NodeUtil.newExpr(assign);
      expr.copyInformationFromForTree(stmtBeginning);
      stmtBeginning.getParent().addChildAfter(expr, stmtBeginning);
    }


    /**
     * Create a factory method assignment node.
     * e.g.
     * <code>
     * function Foo(){}
     * Foo.jsomp$newInstance = function() {...
     * ---------------------^
     * </code>
     * @param constructorName The qualified name of the constructor function.
     * @param block The factory method body node.
     * @return An assignment node.
     */
    private Node createFactoryAssignmentNode(String constructorName, Node block) {
      String factoryName = createFactoryMethodName(constructorName);
      Node factoryNameNode =
          NodeUtil.newQualifiedNameNode(convention, factoryName);

      Node paramNameNode = IR.name(DIConsts.BINDINGS_REPO_NAME);
      Node functionNode =
          IR.function(IR.name(""), IR.paramList(paramNameNode), block);
      Node assign = IR.assign(factoryNameNode, functionNode);
      return assign;
    }


    /**
     * Make the factory method name.
     * @param constructorName The qualified name of the constructor function. 
     * @return The factory method name.
     */
    private String createFactoryMethodName(String constructorName) {
      return constructorName + "." + DIConsts.FACTORY_METHOD_NAME;
    }


    /**
     * Insert method injection calls to the factory method body.
     * This method generate the or-expression here
     * because the compiler can not determine which method is has been
     * the subject of dependency injection.
     * 
     * @param block The factory method body.
     * @param newCall The new call expression.
     */
    private void addMethodInjections(Node block, Node newCall) {
      List<MethodInjectionInfo> methodInjectionInfoList =
          constructorInfo.getMethodInjectionList();
      
      //If the method injection target is not specified
      //create a simple return statement.
      if (methodInjectionInfoList.size() > 0) {
        Node instance = IR.name(INSTANCE_NAME);
        block.addChildToBack(NodeUtil.newVarNode(INSTANCE_NAME, newCall));
        addMethodCalls(block, methodInjectionInfoList);
        block.addChildToBack(IR.returnNode(instance));
      } else {
        block.addChildToBack(IR.returnNode(newCall));
      }
    }


    /**
     * Create and-expression including a method calling.
     * @param block The factory method body.
     * @param methodInjectionInfoList The list of the target method name.
     */
    private void addMethodCalls(Node block, List<MethodInjectionInfo> methodInjectionInfoList) {
      for (MethodInjectionInfo methodInjectionInfo : methodInjectionInfoList) {
        String methodName = methodInjectionInfo.getMethodName();
        Node getprop = NodeUtil.newQualifiedNameNode(convention, INSTANCE_NAME + "." + methodName);
        Node methodCall = NodeUtil.newCallNode(getprop);
        addParameters(methodCall, methodInjectionInfo.getParameterList());
        Node and = IR.and(getprop.cloneTree(), methodCall);
        block.addChildToBack(IR.exprResult(and));
      }
    }


    /**
     * Append parameter nodes to a call node.
     * @param callNode A function call node.
     * @param paramList The list of parameter names.
     */
    private void addParameters(Node callNode, List<String> paramList) {
      for (String param : paramList) {
        Node getprop = NodeUtil.newQualifiedNameNode(convention, DIConsts.BINDINGS_REPO_NAME + "."
            + DIProcessor.toGetter(param));
        Node call = NodeUtil.newCallNode(getprop);

        //If a parameter name is ends with "Provider",
        //the compiler create code which pass the factory method itself directly.
        //e.g.
        //function Foo(barProvider){}
        //Foo.jscomp$newInstance = function(bindings) {
        //  return new Foo(bindings.getBar);
        //}
        if (param.endsWith("Provider")) {
          callNode.addChildToBack(call.getFirstChild());
        } else {
          callNode.addChildToBack(call);
        }
      }
    }
  }


  /**
   * Transform all bindings which binded by 'toInstance' method to the property
   * of module. If binding which binded by 'to' method is found, that code block
   * is removed.
   */
  private final class BindingRewriter implements NodeRewriter {
    private BindingInfo bindingInfo;


    public BindingRewriter(BindingInfo bindingInfo) {
      this.bindingInfo = bindingInfo;
    }


    @Override
    public void rewrite() {
      if (!bindingInfo.isRewrited()) {
        bindingInfo.setRewrited();
        Node n = bindingInfo.getBindCallNode();
        // The name of the binding.
        // binder.bind('foo') <- this.
        String bindingName = bindingInfo.getName();
        // The value node of binding.
        // binder.bind('foo').to(foo.bar.baz.Class) <- this.
        Node expression = bindingInfo.getBindedExpressionNode();

        Node bindingNameNode = IR.string(DIProcessor.toGetter(bindingName));

        Node propNode = createBindingAccessor(bindingName, bindingNameNode);
        Node assign = null;
        boolean isProvider = false;

        switch (bindingInfo.getBindingType()) {
        case TO:
          assign = processConstructorBinding(bindingName, expression, propNode);
          if (assign == null) {
            return;
          }
          break;

        case TO_PROVIDER:
          isProvider = true;

        case TO_INSTANCE:
          assign = processInstanceBinding(n, bindingName, expression, propNode, isProvider);
        }

        assign.copyInformationFromForTree(n);
        // Replace a bind call node by a 'this' assignment node.
        DIProcessor.replaceNode(n, assign);
        compiler.reportCodeChange();
      }
    }


    private Node processInstanceBinding(
        Node n,
        String bindingName,
        Node expression,
        Node propNode,
        boolean isProvider) {
      Node assign;
      Node block = IR.block();
      Node function = IR.function(IR.name(""), IR.paramList(), block);
      assign = IR.assign(propNode, function);

      Node ret;

      if (expression.isName() || NodeUtil.isGet(expression)) {
        ret = expression.cloneTree();
      } else {
        Node beginning = DIProcessor.getStatementBeginningNode(n);
        Node clonedProp = propNode.cloneTree();
        clonedProp.getLastChild().setString("_" + bindingName);
        Node assignment = IR.assign(clonedProp, expression.cloneTree());
        Node expr = NodeUtil.newExpr(assignment);
        expr.copyInformationFromForTree(n);
        beginning.getParent().addChildBefore(expr, beginning);

        ret = createNonRefBindingAccessorBody(expression, isProvider, clonedProp);

      }
      block.addChildToBack(IR.returnNode(ret));
      return assign;
    }


    private Node processConstructorBinding(String bindingName, Node expression, Node propNode) {
      Node assign;
      Preconditions.checkArgument(expression.isName() || NodeUtil.isGet(expression));
      String name = expression.getQualifiedName();
      Preconditions.checkArgument(!Strings.isNullOrEmpty(name));
      if (diInfo.getConstructorInfo(name) == null) {
        DIProcessor.reportClassNotFound(compiler, expression, name);
        return null;
      }

      new FactoryInjector(diInfo.getConstructorInfo(name)).process();

      Node newInstanceCall = NodeUtil.newCallNode(
          NodeUtil.newQualifiedNameNode(convention, name + ".jscomp$newInstance"),
          IR.name(DIConsts.BINDINGS_REPO_NAME));

      if (bindingInfo.isSingleton()) {
        String accessor = DIConsts.BINDINGS_REPO_NAME + "._singleton$" + bindingName;
        Node singleton = NodeUtil.newQualifiedNameNode(convention, accessor);
        Node firstAssign = IR.assign(singleton.cloneTree(), newInstanceCall);
        newInstanceCall = IR.or(singleton.cloneTree(), firstAssign);
      }

      assign = IR.assign(
          propNode,
          IR.function(IR.name(""), IR.paramList(),
              IR.block(IR.returnNode(newInstanceCall))));
      return assign;
    }


    private Node createBindingAccessor(String bindingName, Node bindingNameNode) {
      return DIProcessor.isValidIdentifier(bindingName) ?
          IR.getprop(IR.name(DIConsts.BINDINGS_REPO_NAME), bindingNameNode) : IR.getelem(
              IR.name(DIConsts.BINDINGS_REPO_NAME),
              bindingNameNode);
    }


    private Node createNonRefBindingAccessorBody(Node expression, boolean isProvider,
        Node clonedProp) {
      Node ret;
      if (isProvider) {
        ret = processProviderBinding(expression, clonedProp);
      } else {
        ret = clonedProp.cloneTree();
      }
      return ret;
    }


    private Node processProviderBinding(Node expression, Node clonedProp) {
      Node ret;
      Node providerCall = NodeUtil.newCallNode(clonedProp.cloneTree());
      Node paramList = NodeUtil.getFunctionParameters(expression);
      for (Node paramNode : paramList.children()) {
        String param = paramNode.getString();
        Node bindingGet = NodeUtil.newQualifiedNameNode(convention,
            DIConsts.BINDINGS_REPO_NAME + "." + DIProcessor.toGetter(param));
        providerCall.addChildToBack(NodeUtil.newCallNode(bindingGet));
      }
      ret = providerCall;
      return ret;
    }
  }


  /**
   * Inlining all interceptors.
   */
  private final class InterceptorRewriter implements NodeRewriter {
    private InterceptorInfo interceptorInfo;


    public InterceptorRewriter(InterceptorInfo interceptorInfo) {
      this.interceptorInfo = interceptorInfo;
    }


    @Override
    public void rewrite() {
      Node n = interceptorInfo.getInterceptorCallNode();

      Node statementBeginningNode = DIProcessor.getStatementBeginningNode(n);
      Preconditions.checkNotNull(statementBeginningNode);

      // Rewrite all methodInvocation access.
      MethodInvocationRewriter methodInvocationRewriter = new MethodInvocationRewriter(
          interceptorInfo);
      methodInvocationRewriter.rewrite();

      if (methodInvocationRewriter.isRewrited()) {
        compiler.reportCodeChange();
      }

      String interceptorName = DIConsts.INTERCEPTOR_NAME + interceptorId;
      replaceInterceptorCallToAssignment(statementBeginningNode, interceptorName);

      interceptorInfo.setName(interceptorName);
      interceptorId++;
      compiler.reportCodeChange();
    }


    /**
     * Replace 'bindInterceptor' call node to this assignment node.
     * binder.bindInterceptor(...) -> this.jscomp$interceptor$0 = function()
     * {...}
     * 
     * @param function
     *          A node of the interceptor function.
     * @param statementBeginningNode
     *          The beginning node of statement.
     * @param interceptorName
     *          Name of the interceptor.
     */
    private void replaceInterceptorCallToAssignment(Node statementBeginningNode,
        String interceptorName) {
      Node function = interceptorInfo.getInterceptorNode();
      Node interceptorNameNode = IR.getprop(IR.thisNode(), IR.string(interceptorName));
      function.detachFromParent();
      Node expr = NodeUtil.newExpr(IR.assign(interceptorNameNode, function));
      expr.copyInformationFromForTree(statementBeginningNode);
      DIProcessor.replaceNode(statementBeginningNode, expr);
    }


    private final class MethodInvocationRewriter implements NodeRewriter {
      private static final String CONTEXT = "jscomp$methodInvocation$context";

      private static final String ARGS = "jscomp$methodInvocation$args";

      private static final String CLASS_NAME = "jscomp$methodInvocation$constructorName";

      private static final String METHOD_NAME = "jscomp$methodInvocation$methodName";

      private static final String PROCEED = "jscomp$methodInvocation$proceed";

      private boolean isRewrited = false;

      // The new parameters of replaced interceptor function.
      private final ImmutableList<Node> paramList = new ImmutableList.Builder<Node>()
          .add(IR.name(CONTEXT))
          .add(IR.name(ARGS))
          .add(IR.name(CLASS_NAME))
          .add(IR.name(METHOD_NAME))
          .add(IR.name(PROCEED))
          .build();

      private InterceptorInfo interceptorInfo;


      public MethodInvocationRewriter(InterceptorInfo interceptorInfo) {
        this.interceptorInfo = interceptorInfo;
        Node interceptorNode = interceptorInfo.getInterceptorNode();
        attatchJSDocInfo(interceptorNode);
        replaceParamList();
      }


      /**
       * Attach JSDocInfo to a interceptor function node.
       * 
       * @param interceptorNode
       */
      private void attatchJSDocInfo(Node interceptorNode) {
        JSDocInfoBuilder jsdocInfoBuilder = new JSDocInfoBuilder(false);
        Node anyType = IR.string("*");
        Node stringType = IR.string("string");
        Node arrayType = IR.string("Array");
        Node functionType = IR.string("Function");

        String sourceFileName = interceptorNode.getSourceFileName();
        jsdocInfoBuilder.recordParameter(CONTEXT,
            new JSTypeExpression(anyType, sourceFileName));
        jsdocInfoBuilder.recordParameter(ARGS,
            new JSTypeExpression(arrayType, sourceFileName));
        jsdocInfoBuilder.recordParameter(CLASS_NAME,
            new JSTypeExpression(stringType, sourceFileName));
        jsdocInfoBuilder.recordParameter(METHOD_NAME, new JSTypeExpression(stringType.cloneNode(),
            sourceFileName));
        jsdocInfoBuilder.recordParameter(PROCEED, new JSTypeExpression(functionType.cloneNode(),
            sourceFileName));

        jsdocInfoBuilder.build(interceptorNode);
      }


      /**
       * Replace all parameters of a interceptor function node to the new
       * parameters.
       */
      private void replaceParamList() {
        Node function = interceptorInfo.getInterceptorNode();
        Preconditions.checkArgument(function.isFunction());
        Node paramListNode = NodeUtil.getFunctionParameters(function);
        paramListNode.detachChildren();
        for (Node param : paramList) {
          paramListNode.addChildToBack(param);
        }
      }


      @Override
      public void rewrite() {
        // methodInvocation.proceed()
        for (Node callNode : interceptorInfo.getProceedNodeList()) {
          this.replaceProceed(callNode);
        }

        // methodInvocation.getThis()
        for (Node callNode : interceptorInfo.getThisNodeList()) {
          this.replaceThis(callNode);
        }

        // methodInvocation.getQualifiedName()
        for (Node callNode : interceptorInfo.getQualifiedNameNodeList()) {
          this.replaceGetQualifiedName(callNode);
        }

        // methodInvocation.getMethodName()
        for (Node callNode : interceptorInfo.getMethodNameNodeList()) {
          this.replaceGetMethodName(callNode);
        }

        // methodInvocation.getconstructorName()
        for (Node callNode : interceptorInfo.getConstructorNameNodeList()) {
          this.replaceGetconstructorName(callNode);
        }

        // methodInvocation.getArguments()
        for (Node callNode : interceptorInfo.getArgumentsNodeList()) {
          this.replaceArguments(callNode);
        }
      }


      public boolean isRewrited() {
        return this.isRewrited;
      }


      /**
       * Replace MethodInvocation#proceed call node to simple function applying.
       * 
       * @param callNode
       *          A node of 'methodInvocation.proceed' call.
       */
      private void replaceProceed(Node callNode) {
        isRewrited = true;
        Node call = NodeUtil.newCallNode(
            NodeUtil.newQualifiedNameNode(convention, PROCEED + "." + "apply"),
            IR.name(CONTEXT),
            IR.name(ARGS));
        call.copyInformationFromForTree(callNode);
        DIProcessor.replaceNode(callNode, call);
      }


      /**
       * Replace MethodInvocation#getThis call node to a simple this reference.
       * 
       * @param callNode
       *          A node of 'methodInvocation.getThis' call.
       */
      private void replaceThis(Node callNode) {
        isRewrited = true;
        Node thisNode = IR.name(CONTEXT);
        thisNode.copyInformationFromForTree(callNode);
        DIProcessor.replaceNode(callNode, thisNode);
      }


      /**
       * Replace MethodInvocation#getQualifiedName call node to the simple
       * string concatenations node.
       * 
       * @param callNode
       *          A node of 'methodInvocation.getQualifiedName' call.
       */
      private void replaceGetQualifiedName(Node callNode) {
        isRewrited = true;
        Node prefix = IR.add(IR.name(CLASS_NAME), IR.string("."));
        Node add = IR.add(prefix, IR.name(METHOD_NAME));
        add.copyInformationFromForTree(callNode);
        DIProcessor.replaceNode(callNode, add);
      }


      /**
       * Replace MethodInvocation#getMethodName call node to a simple variable
       * reference node.
       * 
       * @param callNode
       *          A node of 'methodInvocation.getMethodName' call.
       */
      private void replaceGetMethodName(Node callNode) {
        isRewrited = true;
        Node name = IR.name(METHOD_NAME);
        name.copyInformationFromForTree(callNode);
        DIProcessor.replaceNode(callNode, name);
      }


      /**
       * Replace MethodInvocation#getconstructorName call node to a simple
       * variable reference node.
       * 
       * @param callNode
       *          A node of 'methodInvocation.getconstructorName' call.
       */
      private void replaceGetconstructorName(Node callNode) {
        isRewrited = true;
        Node name = IR.name(CLASS_NAME);
        name.copyInformationFromForTree(callNode);
        DIProcessor.replaceNode(callNode, name);
      }


      /**
       * Replace MethodInvocation#getArguments call node to a simple variable
       * reference node.
       * 
       * @param callNode
       *          A node of 'methodInvocation.getArguments' call.
       */
      private void replaceArguments(Node callNode) {
        isRewrited = true;
        Node name = IR.name(ARGS);
        name.copyInformationFromForTree(callNode);
        DIProcessor.replaceNode(callNode, name);
      }
    }
  }


  /**
   * The configuration for dependencies resolving. The 'configuration' clone
   * ConstructorInfo and PrototypeInfo for adding additional informations of the
   * instantiation.
   */
  private final class ModuleInitializerConfig {
    private Map<String, BindingInfo> allBindingInfoMap = Maps.newHashMap();

    private Map<String, ConstructorInfo> clonedMap = Maps.newHashMap();

    private ModuleInitializerInfo moduleInitInfo;

    private Map<String, List<InterceptorInfo>> interceptorMap = Maps.newHashMap();


    public ModuleInitializerConfig(ModuleInitializerInfo moduleInitInfo) {
      this.moduleInitInfo = moduleInitInfo;
      initBindingMap();
      cloneClassInfo();
    }


    /**
     * Propagate all binding information.
     */
    private void initBindingMap() {
      for (String moduleName : moduleInitInfo.getConfigModuleList()) {
        ModuleInfo moduleInfo = diInfo.getModuleInfo(moduleName);
        if (moduleInfo != null) {
          ArrayListMultimap<String, BindingInfo> bindingInfoMap = moduleInfo.getBindingInfoMap();
          for (String key : bindingInfoMap.keys()) {
            allBindingInfoMap.put(key, bindingInfoMap.get(key).get(0));
          }
          List<InterceptorInfo> infoList = diInfo.getInterceptorInfo(moduleName);
          if (infoList != null) {
            List<InterceptorInfo> list = Lists.newArrayList();
            String varName = DIProcessor.newValidVarName(moduleName);
            interceptorMap.put(varName, list);
            for (InterceptorInfo interceptorInfo : infoList) {
              interceptorInfo.setModuleName(varName);
              list.add(interceptorInfo);
            }
          }
        }
      }
    }


    /**
     * Clone all ConstructorInfo.
     */
    private void cloneClassInfo() {
      for (ConstructorInfo constructorInfo : diInfo.getConstructorInfoMap().values()) {
        ConstructorInfo newClassInfo = (ConstructorInfo) constructorInfo.clone();
        clonedMap.put(newClassInfo.getConstructorName(), newClassInfo);
      }

      for (ConstructorInfo constructorInfo : clonedMap.values()) {
        bindInterceptorInfo(constructorInfo);
      }
    }


    /**
     * Propagate InterceptorInfo by to matching in compilation time.
     * 
     * @param constructorInfo
     *          The ConstructorInfo of matching target.
     */
    private void bindInterceptorInfo(ConstructorInfo constructorInfo) {
      if (!constructorInfo.hasInterceptorFlag()) {
        for (List<InterceptorInfo> interceptorInfoList : interceptorMap.values()) {
          for (InterceptorInfo interceptorInfo : interceptorInfoList) {
            if (isMatchClass(interceptorInfo, constructorInfo)) {
              boolean hasMatchedMethod = addInterceptorInfoToPrototypeIfMatched(constructorInfo,
                  interceptorInfo);
              if (hasMatchedMethod) {
                constructorInfo.setInterceptorFlag();
              }
            }
          }
        }
      }
    }


    /**
     * Bind the InterceptorInfo to the PrototypeInfo if method is matched.
     * 
     * @param constructorInfo
     *          The ConstructorInfo of matching target.
     * @param interceptorInfo
     *          The InterceptorInfo of current checking.
     * @return a method is matched or not.
     */
    private boolean addInterceptorInfoToPrototypeIfMatched(ConstructorInfo constructorInfo,
        InterceptorInfo interceptorInfo) {
      boolean hasMatchedMethod = false;
      Map<String, PrototypeInfo> prototypeInfoMap = constructorInfo.getPrototypeInfoMap();
      for (PrototypeInfo prototypeInfo : prototypeInfoMap.values()) {
        if (isMatchMethod(prototypeInfo, interceptorInfo)) {
          if (prototypeInfo.isAmbiguous()) {
            DIProcessor.report(compiler, prototypeInfo.getFunction(),
                DIOptimizerInfoCollector.MESSAGE_PROTOTYPE_FUNCTION_IS_AMBIGUOUS,
                prototypeInfo.getMethodName(), constructorInfo.getConstructorName());
          } else if (!prototypeInfo.hasInterceptorInfo(interceptorInfo)) {
            prototypeInfo.addInterceptor(interceptorInfo);
            hasMatchedMethod = true;
          }
        }
      }
      return hasMatchedMethod;
    }


    /**
     * Check the method is matched with a interceptor binding.
     * 
     * @param prototypeInfo
     *          A PrototypeInfo of matching target.
     * @param interceptorInfo
     *          current matcher.
     * @return Matched or not.
     */
    private boolean isMatchMethod(PrototypeInfo prototypeInfo, InterceptorInfo interceptorInfo) {
      switch (interceptorInfo.getMethodMatchType()) {

      case LIKE: {
        String methodMatcher = interceptorInfo.getMethodMatcher();
        String methodMatcherReg = "^" + methodMatcher.replaceAll("\\*", ".*");
        return prototypeInfo.getMethodName().matches(methodMatcherReg);
      }

      case ANY:
        return true;
      }

      return false;
    }


    /**
     * Check the class is matched with a current interceptor binding.
     * 
     * @param interceptorInfo
     *          Current matcher.
     * @param constructorInfo
     *          The ConstructorInfo of current matching target.
     * @return matched or not.
     */
    private boolean isMatchClass(InterceptorInfo interceptorInfo, ConstructorInfo constructorInfo) {
      ClassMatchType classMatchType = interceptorInfo.getConstructorMatchType();
      if (classMatchType != null) {
        String constructorName = constructorInfo.getConstructorName();
        switch (classMatchType) {

        case IN_NAMESPACE: {
          String reg = "^" + interceptorInfo.getConstructorMatcher().replaceAll("\\.", "\\.") + "$";
          int index = constructorName.lastIndexOf('.');
          if (index > -1) {
            return constructorName.substring(0, index).matches(reg);
          }
          return constructorName.matches(reg);
        }

        case SUB_NAMESPACE: {
          String reg = "^" + interceptorInfo.getConstructorMatcher().replaceAll("\\.", "\\.")
              + ".*";
          return constructorName.matches(reg);
        }

        case SUBCLASS_OF:
          return this.checkTypeHierarchy(constructorInfo, interceptorInfo);

        case INSTANCE_OF:
          return interceptorInfo.getConstructorMatcher().equals(constructorName);

        case ANY:
          return true;
        }
      }

      return false;
    }


    /**
     * Check base types for a subclassOf matcher.
     * 
     * @param constructorInfo
     *          A ConstructorInfo of current matching target.
     * @param interceptorInfo
     *          Current matcher.
     * @return matched or not.
     */
    private boolean checkTypeHierarchy(ConstructorInfo constructorInfo,
        InterceptorInfo interceptorInfo) {
      JSDocInfo jsDocInfo = constructorInfo.getJSDocInfo();
      if (jsDocInfo != null) {
        JSTypeExpression exp = jsDocInfo.getBaseType();
        if (exp != null) {
          Node typeNode = exp.getRoot().getFirstChild();
          if (this.checkType(typeNode, interceptorInfo.getConstructorMatcher())) {
            return true;
          } else if (typeNode.isString()) {
            ConstructorInfo baseInfo = clonedMap.get(typeNode.getString());
            if (baseInfo != null) {
              return this.checkTypeHierarchy(baseInfo, interceptorInfo);
            }
          }
        }
      }
      return false;
    }


    /**
     * Compare a type name.
     * 
     * @param typeNode
     *          A JSDocInfo base type node.
     * @param typeName
     *          A name of matcher type.
     * @return
     */
    private boolean checkType(Node typeNode, String typeName) {
      if (typeNode.isString()) {
        String name = typeNode.getString();
        if (name.equals(typeName)) {
          return true;
        }
      }
      return false;
    }


    /**
     * @return the allBindingInfoMap
     */
    public Map<String, BindingInfo> getAllBindingInfoMap() {
      return allBindingInfoMap;
    }


    /**
     * @return the clonedMap
     */
    public Map<String, ConstructorInfo> getClonedMap() {
      return clonedMap;
    }


    /**
     * @return the moduleInitInfo
     */
    public ModuleInitializerInfo getModuleInitInfo() {
      return moduleInitInfo;
    }
  }


  private final class ModuleInitializerRewriter implements NodeRewriter {

    private Map<String, BindingInfo> allBindingInfoMap;

    private Map<String, ConstructorInfo> clonedMap;

    private ModuleInitializerInfo moduleInitializerInfo;


    public ModuleInitializerRewriter(ModuleInitializerConfig moduleInitializerConfig) {
      allBindingInfoMap = moduleInitializerConfig.getAllBindingInfoMap();
      clonedMap = moduleInitializerConfig.getClonedMap();
      moduleInitializerInfo = moduleInitializerConfig.getModuleInitInfo();
      initEagerSingletons();
    }


    @Override
    public void rewrite() {
      for (InjectorInfo injectorInfo : this.moduleInitializerInfo.getInjectorInfoList()) {
        new InjectorRewriter(injectorInfo).rewrite();
      }
    }


    /**
     * Initialize the constructors that are specified as Scopes.EAGER_SINGLETON.
     * That constructors are instantiated regardless of using or not.
     */
    private void initEagerSingletons() {
      for (BindingInfo bindingInfo : allBindingInfoMap.values()) {
        Node exp = bindingInfo.getBindedExpressionNode();

        if (exp.isName() || NodeUtil.isGet(exp)) {
          String qname = exp.getQualifiedName();
          if (!Strings.isNullOrEmpty(qname)) {
            if (clonedMap.containsKey(qname)) {
              ConstructorInfo constructorInfo = clonedMap.get(qname);
              constructorInfo.setScopeType(bindingInfo.getScopeType());
              constructorInfo.setBindingInfo(bindingInfo);
              if (bindingInfo.isEager()) {
                Node block = NodeUtil.getFunctionBody(moduleInitializerInfo.getModuleInitCall());
                Node getprop = NodeUtil.newQualifiedNameNode(convention,
                    bindingInfo.getBindingAccessorName());
                Node call = NodeUtil.newCallNode(getprop);
                Node expr = NodeUtil.newExpr(call);
                expr.copyInformationFromForTree(block);
                block.addChildToFront(expr);
              }
            }
          }
        }
      }
    }


    /**
     * Rewrite all injector.getInstance and injector.getInstanceByName call.
     */
    private final class InjectorRewriter implements NodeRewriter {
      private InjectorInfo injectorInfo;


      public InjectorRewriter(InjectorInfo injectorInfo) {
        this.injectorInfo = injectorInfo;
      }


      @Override
      public void rewrite() {
        Node n = injectorInfo.getCallNode();
        String name = injectorInfo.getName();
        if (!injectorInfo.isName()) {
          this.inliningGetInstanceCall(n, name);
        } else {
          this.inliningGetInstanceByNameCall(n, name);
        }
      }


      /**
       * Inlining injector.getInstance call node.
       * 
       * @param n
       *          A node of injector.getInstance call.
       * @param constructorName
       *          The name of target constructor function.
       */
      private void inliningGetInstanceCall(Node n, String constructorName) {
        ConstructorInfo info = clonedMap.get(constructorName);
        Node child = null;

        if (info != null) {
          new FactoryInjector(info).process();
          child =
              NodeUtil.newQualifiedNameNode(convention,
                  constructorName + ".jscomp$newInstance");
          child = NodeUtil.newCallNode(child, IR.name(DIConsts.BINDINGS_REPO_NAME));
          child.copyInformationFromForTree(n);
          DIProcessor.replaceNode(n, child);
          compiler.reportCodeChange();
        } else {
          DIProcessor.reportClassNotFound(compiler, n, constructorName);
        }
      }


      /**
       * Inlining injector.getInstanceByName call.
       * 
       * @param n
       *          A node of injector.getInstanceByName call.
       * @param bindingName
       *          The name of constructor function.
       */
      private void inliningGetInstanceByNameCall(Node n, String bindingName) {
        if (allBindingInfoMap.containsKey(bindingName)) {
          Node newChild = NodeUtil.newQualifiedNameNode(convention, DIConsts.BINDINGS_REPO_NAME
              + "." + DIProcessor.toGetter(bindingName));
          newChild = NodeUtil.newCallNode(newChild);
          DIProcessor.replaceNode(n, newChild);
          compiler.reportCodeChange();
        }
      }
    }
  }


  public void rewriteBinding(ModuleInfo moduleInfo) {
    for (BindingInfo bindingInfo : moduleInfo.getBindingInfoMap().values()) {
      new BindingRewriter(bindingInfo).rewrite();
    }

    for (InterceptorInfo interceptorInfo : moduleInfo.getInterceptorInfoList()) {
      new InterceptorRewriter(interceptorInfo).rewrite();
    }

    Node function = moduleInfo.getModuleMethodNode();
    if (function != null) {
      // This rewriting process is cause the error or warning
      // if the compiler option "--jscomp_error checkTypes",
      // "--jscomp_warnings checkTypes"
      // or "--warning_level VERBOSE" is specified
      // because rewritten method is called with no arguments.
      // So remove all parameters and jsdocs from this method.
      Node paramList = NodeUtil.getFunctionParameters(function);
      paramList.detachChildren();
      paramList.addChildToBack(IR.name(DIConsts.BINDINGS_REPO_NAME));

      this.removeJSDocInfoFromBindingMethod(function);
      compiler.reportCodeChange();
    }
  }


  /**
   * Remove JSDocInfo from a assignment node of Module.prototype.configure.
   * 
   * @param function
   *          a method definition node.
   */
  private void removeJSDocInfoFromBindingMethod(Node function) {
    Node assign = function;
    while (assign != null && !assign.isAssign()) {
      assign = assign.getParent();
    }
    assign.setJSDocInfo(null);
  }


  private void rewriteInjection(ModuleInitializerInfo moduleInitializerInfo) {
    ModuleInitializerConfig moduleInitializerConfig = new ModuleInitializerConfig(
        moduleInitializerInfo);
    new ModuleInitializerRewriter(moduleInitializerConfig).rewrite();

    Node function = moduleInitializerInfo.getModuleInitCall();
    Node moduleInitCall = function.getParent();
    Node injector = NodeUtil.getFunctionParameters(function).getFirstChild();
    List<String> moduleConfigList = moduleInitializerInfo.getConfigModuleList();

    // This rewriting process is cause the error or warning
    // "--jscomp_error checkTypes", "--jscomp_warnings checkTypes"
    // or "--warning_level VERBOSE" is specified because it change
    // the function into a anonymous function which calling with no
    // arguments.
    // So remove all parameters and jsdocs from this method.
    injector.detachFromParent();
    function.setJSDocInfo(null);

    rewriteCall(moduleConfigList, moduleInitCall, function);
    compiler.reportCodeChange();
  }


  private void rewriteCall(List<String> moduleConfigList, Node moduleInitCall, Node function) {
    function.detachFromParent();
    Node block = NodeUtil.getFunctionBody(function);
    Node call = NodeUtil.newCallNode(function);

    call.copyInformationFromForTree(moduleInitCall);
    DIProcessor.replaceNode(moduleInitCall, call);
    compiler.reportCodeChange();

    List<String> copied = Lists.newArrayList();
    copied.addAll(moduleConfigList);
    Collections.reverse(copied);

    Node bindingsDef = NodeUtil.newVarNode(DIConsts.BINDINGS_REPO_NAME, IR.objectlit());
    bindingsDef.copyInformationFromForTree(block);

    for (String config : copied) {
      Node newCall = IR.newNode(NodeUtil.newQualifiedNameNode(convention, config));
      Node configureCallNode = NodeUtil.newCallNode(
          IR.getprop(newCall, IR.string(DIConsts.MODULE_SETUP_METHOD_NAME)),
          IR.name(DIConsts.BINDINGS_REPO_NAME));

      Node expr = NodeUtil.newExpr(configureCallNode);
      expr.copyInformationFromForTree(block);

      if (block.getChildCount() > 0) {
        block.addChildBefore(expr, block.getFirstChild());
      } else {
        block.addChildToFront(expr);
      }
      compiler.reportCodeChange();
    }

    block.addChildToFront(bindingsDef);
    compiler.reportCodeChange();
  }
}
