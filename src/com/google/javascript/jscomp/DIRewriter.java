package com.google.javascript.jscomp;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import com.google.common.base.Function;
import com.google.common.base.Objects;
import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.common.collect.Collections2;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.javascript.jscomp.DIConsts.ClassMatchType;
import com.google.javascript.jscomp.DIInfo.BindingInfo;
import com.google.javascript.jscomp.DIInfo.ClassInfo;
import com.google.javascript.jscomp.DIInfo.InjectorInfo;
import com.google.javascript.jscomp.DIInfo.InterceptorInfo;
import com.google.javascript.jscomp.DIInfo.ModuleInfo;
import com.google.javascript.jscomp.DIInfo.ModuleInitializerInfo;
import com.google.javascript.jscomp.DIInfo.PrototypeInfo;
import com.google.javascript.rhino.IR;
import com.google.javascript.rhino.JSDocInfo;
import com.google.javascript.rhino.JSDocInfoBuilder;
import com.google.javascript.rhino.JSTypeExpression;
import com.google.javascript.rhino.Node;
import com.google.javascript.rhino.Token;

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
 *                                         jscomp$methodInvocation$className,
 *                                         jscomp$methodInvocation$methodName,
 *                                         jscomp$methodInvocation$proceed) {
 *      console.log("log call before : " + jscomp$methodInvocation$className + ("." + jscomp$joinPoint$methodName));
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
final class DIRewriter {

  private final DIInfo diInfo;

  private final AbstractCompiler compiler;

  private final CodingConvention convention;

  private int interceptorId = 0;


  public DIRewriter(AbstractCompiler compiler, DIInfo dIInfo) {
    this.compiler = compiler;
    this.convention = compiler.getCodingConvention();
    this.diInfo = dIInfo;
  }


  public void rewrite() {
    for (ModuleInfo moduleInfo : diInfo.getModuleInfoMap().values()) {
      this.rewriteBinding(moduleInfo);
    }
    for (ModuleInitializerInfo moduleInitInfo : diInfo.getModuleInitInfoList()) {
      this.rewriteInjection(moduleInitInfo);
    }
  }


  /**
   * The interface of rewriters.
   */
  private interface NodeRewriter {
    public void rewrite();
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
      Node n = bindingInfo.getBindCallNode();
      // The name of the binding.
      // binder.bind('foo') <- this.
      String bindingName = bindingInfo.getName();
      // The value node of binding.
      // binder.bind('foo').to(foo.bar.baz.Class) <- this.
      Node expression = bindingInfo.getBindedExpressionNode();

      Node propNode = IR.getprop(IR.thisNode(), IR.string(bindingName));
      Node assign = null;

      switch (bindingInfo.getBindingType()) {
      case TO: {
        Preconditions.checkArgument(expression.isName() || NodeUtil.isGet(expression));
        String name = expression.getQualifiedName();
        Preconditions.checkArgument(!Strings.isNullOrEmpty(name));
        Preconditions.checkNotNull(diInfo.getClassInfo(name));

        ClassInfo classInfo = diInfo.getClassInfo(name);
        Node newCall = IR.newNode(NodeUtil.newQualifiedNameNode(convention, name));
        Node paramList = IR.paramList();
        for (String param : classInfo.getParamList()) {
          paramList.addChildToBack(IR.name(param));
          newCall.addChildToBack(IR.name(param));
        }
        assign = IR.assign(propNode,
            IR.function(IR.name(""), paramList, IR.block(IR.returnNode(newCall))));
      }
        break;

      case TO_PROVIDER:
      case TO_INSTANCE: {
        assign = IR.assign(propNode, expression.cloneTree());
      }
      }

      assign.copyInformationFromForTree(n);
      // Replace a bind call node by a 'this' assignment node.
      DIProcessor.replaceNode(n, assign);
      compiler.reportCodeChange();
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

      private static final String CLASS_NAME = "jscomp$methodInvocation$className";

      private static final String METHOD_NAME = "jscomp$methodInvocation$methodName";

      private static final String PROCEED = "jscomp$methodInvocation$proceed";

      private boolean isRewrited = false;

      // The new parameters of replaced interceptor function.
      private final ImmutableList<Node> paramList = new ImmutableList.Builder<Node>()
          .add(Node.newString(Token.NAME, CONTEXT))
          .add(Node.newString(Token.NAME, ARGS))
          .add(Node.newString(Token.NAME, CLASS_NAME))
          .add(Node.newString(Token.NAME, METHOD_NAME))
          .add(Node.newString(Token.NAME, PROCEED))
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
        Node anyType = Node.newString(Token.NAME, "*");
        Node stringType = Node.newString(Token.NAME, "string");
        Node arrayType = Node.newString(Token.NAME, "Array");
        Node functionType = Node.newString(Token.NAME, "Function");

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

        // methodInvocation.getClassName()
        for (Node callNode : interceptorInfo.getClassNameNodeList()) {
          this.replaceGetClassName(callNode);
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
       * Replace MethodInvocation#getClassName call node to a simple variable
       * reference node.
       * 
       * @param callNode
       *          A node of 'methodInvocation.getClassName' call.
       */
      private void replaceGetClassName(Node callNode) {
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
   * ClassInfo and PrototypeInfo for adding additional informations of the
   * instantiation.
   */
  private final class ModuleInitializerConfig {
    private Map<String, BindingInfo> allBindingInfoMap = Maps.newHashMap();

    private Map<String, ClassInfo> clonedMap = Maps.newHashMap();

    private DependenciesResolver dependenciesResolver;

    private ModuleInitializerInfo moduleInitInfo;

    private Map<String, List<InterceptorInfo>> interceptorMap = Maps.newHashMap();


    public ModuleInitializerConfig(ModuleInitializerInfo moduleInitInfo) {
      this.moduleInitInfo = moduleInitInfo;
      initBindingMap();
      cloneClassInfo();
      dependenciesResolver = new DependenciesResolver(allBindingInfoMap, clonedMap,
          moduleInitInfo, compiler);
    }


    /**
     * Propagate all binding information.
     */
    private void initBindingMap() {
      for (String moduleName : moduleInitInfo.getConfigModuleList()) {
        ModuleInfo moduleInfo = diInfo.getModuleInfo(moduleName);
        if (moduleInfo != null) {
          allBindingInfoMap.putAll(moduleInfo.getBindingInfoMap());
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
     * Clone all ClassInfo.
     */
    private void cloneClassInfo() {
      for (ClassInfo classInfo : diInfo.getClassInfoMap().values()) {
        ClassInfo newClassInfo = (ClassInfo) classInfo.clone();
        clonedMap.put(newClassInfo.getClassName(), newClassInfo);
      }

      for (ClassInfo classInfo : clonedMap.values()) {
        bindInterceptorInfo(classInfo);
      }
    }


    /**
     * Propagate InterceptorInfo by to matching in compilation time.
     * 
     * @param classInfo
     *          The ClassInfo of matching target.
     */
    private void bindInterceptorInfo(ClassInfo classInfo) {
      if (!classInfo.hasInterceptorFlag()) {
        for (List<InterceptorInfo> interceptorInfoList : interceptorMap.values()) {
          for (InterceptorInfo interceptorInfo : interceptorInfoList) {
            if (isMatchClass(interceptorInfo, classInfo)) {
              boolean hasMatchedMethod = addInterceptorInfoToPrototypeIfMatched(classInfo,
                  interceptorInfo);
              if (hasMatchedMethod) {
                classInfo.setInterceptorFlag();
              }
            }
          }
        }
      }
    }


    /**
     * Bind the InterceptorInfo to PrototypeInfo if method is matched.
     * 
     * @param classInfo
     *          The ClassInfo of matching target.
     * @param interceptorInfo
     *          The InterceptorInfo of current checking.
     * @return a method is matched or not.
     */
    private boolean addInterceptorInfoToPrototypeIfMatched(ClassInfo classInfo,
        InterceptorInfo interceptorInfo) {
      boolean hasMatchedMethod = false;
      Map<String, PrototypeInfo> prototypeInfoMap = classInfo.getPrototypeInfoMap();
      for (PrototypeInfo prototypeInfo : prototypeInfoMap.values()) {
        if (isMatchMethod(prototypeInfo, interceptorInfo)) {
          if (!prototypeInfo.hasInterceptorInfo(interceptorInfo)) {
            prototypeInfo.addInterceptor(interceptorInfo);
            hasMatchedMethod = true;
          }
        }
      }
      return hasMatchedMethod;
    }


    /**
     * Check the method is matched with interceptor binding.
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
     * @param classInfo
     *          The ClassInfo of current matching target.
     * @return matched or not.
     */
    private boolean isMatchClass(InterceptorInfo interceptorInfo, ClassInfo classInfo) {
      ClassMatchType classMatchType = interceptorInfo.getClassMatchType();
      if (classMatchType != null) {
        String className = classInfo.getClassName();
        switch (classMatchType) {

        case IN_NAMESPACE: {
          String reg = "^" + interceptorInfo.getClassMatcher().replaceAll("\\.", "\\.") + "$";
          int index = className.lastIndexOf('.');
          if (index > -1) {
            return className.substring(0, index).matches(reg);
          }
          return className.matches(reg);
        }

        case SUB_NAMESPACE: {
          String reg = "^" + interceptorInfo.getClassMatcher().replaceAll("\\.", "\\.") + ".*";
          return className.matches(reg);
        }

        case SUBCLASS_OF:
          return this.checkTypeHierarchy(classInfo, interceptorInfo);

        case INSTANCE_OF:
          return interceptorInfo.getClassMatcher().equals(className);

        case ANY:
          return true;
        }
      }

      return false;
    }


    /**
     * Check base types for subclassOf matcher.
     * 
     * @param classInfo
     *          A ClassInfo of current matching target.
     * @param interceptorInfo
     *          Current matcher.
     * @return matched or not.
     */
    private boolean checkTypeHierarchy(ClassInfo classInfo, InterceptorInfo interceptorInfo) {
      JSDocInfo jsDocInfo = classInfo.getJSDocInfo();
      if (jsDocInfo != null) {
        JSTypeExpression exp = jsDocInfo.getBaseType();
        if (exp != null) {
          Node typeNode = exp.getRoot().getFirstChild();
          if (this.checkType(typeNode, interceptorInfo.getClassMatcher())) {
            return true;
          } else if (typeNode.isString()) {
            ClassInfo baseInfo = clonedMap.get(typeNode.getString());
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
    public Map<String, ClassInfo> getClonedMap() {
      return clonedMap;
    }


    /**
     * @return the interceptorBuilder
     */
    public DependenciesResolver getDIProcessor() {
      return this.dependenciesResolver;
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

    private Map<String, ClassInfo> clonedMap;

    private ModuleInitializerInfo moduleInitializerInfo;

    private DependenciesResolver dependenciesResolver;


    public ModuleInitializerRewriter(ModuleInitializerConfig moduleInitializerConfig) {
      allBindingInfoMap = moduleInitializerConfig.getAllBindingInfoMap();
      clonedMap = moduleInitializerConfig.getClonedMap();
      dependenciesResolver = moduleInitializerConfig.getDIProcessor();
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
     * Initialize constructor which is specified as Scopes.EAGER_SINGLETON. It
     * is instantiated regardless of using or not.
     */
    private void initEagerSingletons() {
      for (BindingInfo bindingInfo : allBindingInfoMap.values()) {
        Node exp = bindingInfo.getBindedExpressionNode();

        if (exp.isName() || NodeUtil.isGet(exp)) {
          String qname = exp.getQualifiedName();
          if (!Strings.isNullOrEmpty(qname)) {
            if (clonedMap.containsKey(qname)) {
              ClassInfo classInfo = clonedMap.get(qname);
              classInfo.setScopeType(bindingInfo.getScopeType());
              classInfo.setBindingInfo(bindingInfo);
              dependenciesResolver.makeInstantiateExpression(classInfo);
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
       * @param className
       *          The name of target constructor function.
       */
      private void inliningGetInstanceCall(Node n, String className) {
        ClassInfo info = clonedMap.get(className);
        Node child = null;

        if (info != null) {
          child = dependenciesResolver.makeInstantiateExpression(info);
          child.copyInformationFromForTree(n);
          DIProcessor.replaceNode(n, child);
          compiler.reportCodeChange();
        } else {
          dependenciesResolver.reportClassNotFound(n, className);
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
        Node newChild = null;
        if (allBindingInfoMap.containsKey(bindingName)) {
          BindingInfo bindingInfo = allBindingInfoMap.get(bindingName);

          if (bindingInfo.isProvider()) {
            newChild = dependenciesResolver.makeProviderCall(bindingInfo, false);
          } else {
            Node expression = bindingInfo.getBindedExpressionNode();
            Preconditions.checkArgument(expression.isName() || NodeUtil.isGet(expression));
            String name = expression.getQualifiedName();
            Preconditions.checkArgument(!Strings.isNullOrEmpty(name));
            ClassInfo info = clonedMap.get(name);

            if (info != null) {
              newChild = dependenciesResolver.makeInstantiateExpression(info);
            } else {
              dependenciesResolver.reportClassNotFound(n, name);
            }
          }
        }

        if (newChild != null) {
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
    if (NodeUtil.getFunctionParameters(function).getChildCount() > 0) {
      this.addReturn(function);

      // This rewriting process is cause the error or warning
      // if the compiler option "--jscomp_error checkTypes",
      // "--jscomp_warnings checkTypes"
      // or "--warning_level VERBOSE" is specified
      // because rewritten method is called with no arguments.
      // So remove all parameters and jsdocs from this method.
      NodeUtil.getFunctionParameters(function)
          .detachChildren();
      this.removeJSDocInfoFromBindingMethod(function);
      compiler.reportCodeChange();
    }
  }


  private void addReturn(Node function) {
    Node block = NodeUtil.getFunctionBody(function);
    Node returnStmt = new Node(Token.RETURN, new Node(Token.THIS));
    returnStmt.copyInformationFromForTree(block.getChildCount() > 0 ? block.getLastChild()
        : block);
    block.addChildToBack(returnStmt);
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

    for (String config : copied) {
      String varName = DIProcessor.newValidVarName(config);
      Node newCall = IR.newNode(NodeUtil.newQualifiedNameNode(convention, config));
      Node configureCallNode = NodeUtil.newCallNode(
          IR.getprop(newCall, IR.string(DIConsts.MODULE_SETUP_METHOD_NAME)));

      Node var = NodeUtil.newVarNode(varName, configureCallNode);
      var.copyInformationFromForTree(block);

      if (block.getChildCount() > 0) {
        block.addChildBefore(var, block.getFirstChild());
      } else {
        block.addChildToFront(var);
      }
      compiler.reportCodeChange();
    }
  }
}
