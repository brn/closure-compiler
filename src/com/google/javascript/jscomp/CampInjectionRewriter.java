package com.google.javascript.jscomp;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import com.google.common.base.Function;
import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.common.collect.Collections2;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.javascript.jscomp.CampInjectionConsts.ClassMatchType;
import com.google.javascript.jscomp.CampInjectionInfo.BindingInfo;
import com.google.javascript.jscomp.CampInjectionInfo.ClassInfo;
import com.google.javascript.jscomp.CampInjectionInfo.InjectorInfo;
import com.google.javascript.jscomp.CampInjectionInfo.InterceptorInfo;
import com.google.javascript.jscomp.CampInjectionInfo.ModuleInfo;
import com.google.javascript.jscomp.CampInjectionInfo.ModuleInitializerInfo;
import com.google.javascript.jscomp.CampInjectionInfo.PrototypeInfo;
import com.google.javascript.rhino.JSDocInfo;
import com.google.javascript.rhino.JSDocInfoBuilder;
import com.google.javascript.rhino.JSTypeExpression;
import com.google.javascript.rhino.Node;
import com.google.javascript.rhino.Token;

/**
 * @author Taketoshi Aono
 * 
 *         Rewrite all DI and interceptors. Rewrite process is like follows.
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
final class CampInjectionRewriter {

  private final CampInjectionInfo campInjectionInfo;

  private final AbstractCompiler compiler;

  private final CodingConvention convention;

  private int interceptorId = 0;


  public CampInjectionRewriter(AbstractCompiler compiler, CampInjectionInfo campInjectionInfo) {
    this.compiler = compiler;
    this.convention = compiler.getCodingConvention();
    this.campInjectionInfo = campInjectionInfo;
  }


  public void rewrite() {
    for (ModuleInfo moduleInfo : campInjectionInfo.getModuleInfoMap().values()) {
      this.rewriteBinding(moduleInfo);
    }
    for (ModuleInitializerInfo moduleInitInfo : campInjectionInfo.getModuleInitInfoList()) {
      this.rewriteInjection(moduleInitInfo);
    }
  }


  private interface NodeRewriter {
    public void rewrite();
  }


  private final class BindingRewriter implements NodeRewriter {
    private BindingInfo bindingInfo;


    public BindingRewriter(BindingInfo bindingInfo) {
      this.bindingInfo = bindingInfo;
    }


    @Override
    public void rewrite() {
      Node n = bindingInfo.getBindCallNode();
      String bindingName = n.getFirstChild().getNext().getString();
      Node expression = bindingInfo.getBindedExpressionNode();
      Node tmp = CampInjectionProcessor.getStatementTopNode(n);
      Preconditions.checkNotNull(tmp);

      switch (bindingInfo.getBindingType()) {
      case TO: {
        if (expression.isName() || expression.isGetProp()) {
          String name = expression.getQualifiedName();
          if (name != null && campInjectionInfo.getClassInfo(name) != null) {
            tmp.detachFromParent();
            compiler.reportCodeChange();
            return;
          }
        }
      }
        break;

      case TO_PROVIDER:
      case TO_INSTANCE: {
        String name = CampInjectionConsts.THIS + "." + bindingName;

        Node assign = new Node(Token.ASSIGN,
            CampInjectionProcessor.newQualifiedNameNode(name),
            expression.cloneTree());

        Node expr = NodeUtil.newExpr(assign);

        expr.copyInformationFromForTree(n);

        tmp.getParent().addChildAfter(expr, tmp);
        tmp.detachFromParent();
        compiler.reportCodeChange();
      }
      }
    }
  }


  private final class InterceptorRewriter implements NodeRewriter {
    private InterceptorInfo interceptorInfo;


    public InterceptorRewriter(InterceptorInfo interceptorInfo) {
      this.interceptorInfo = interceptorInfo;
    }


    @Override
    public void rewrite() {
      Node n = interceptorInfo.getInterceptorCallNode();
      Node function = interceptorInfo.getInterceptorNode();
      Node tmp = CampInjectionProcessor.getStatementTopNode(n);
      Preconditions.checkNotNull(tmp);

      MethodInvocationRewriter methodInvocationRewriter = new MethodInvocationRewriter(
          interceptorInfo);
      methodInvocationRewriter.rewrite();

      Node paramList = function.getFirstChild().getNext();

      paramList.detachChildren();
      for (Node param : methodInvocationRewriter.getParamList()) {
        paramList.addChildToBack(param);
      }

      String interceptorName = CampInjectionConsts.THIS + "."
          + CampInjectionConsts.INTERCEPTOR_NAME
          + interceptorId;

      function.detachFromParent();
      Node expr = NodeUtil.newExpr(new Node(Token.ASSIGN,
          CampInjectionProcessor.newQualifiedNameNode(interceptorName), function));

      expr.copyInformationFromForTree(tmp);
      tmp.getParent().addChildAfter(expr, tmp);
      interceptorInfo.setName(CampInjectionConsts.INTERCEPTOR_NAME + interceptorId);
      interceptorId++;
      tmp.detachFromParent();
      compiler.reportCodeChange();
    }


    private final class MethodInvocationRewriter implements NodeRewriter {
      private static final String CONTEXT = "jscomp$methodInvocation$context";

      private static final String ARGS = "jscomp$methodInvocation$args";

      private static final String CLASS_NAME = "jscomp$methodInvocation$className";

      private static final String METHOD_NAME = "jscomp$methodInvocation$methodName";

      private static final String PROCEED = "jscomp$methodInvocation$proceed";

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


      @Override
      public void rewrite() {
        for (Node callNode : interceptorInfo.getProceedNodeList()) {
          this.replaceProceed(callNode);
        }

        for (Node callNode : interceptorInfo.getThisNodeList()) {
          this.replaceThis(callNode);
        }

        for (Node callNode : interceptorInfo.getQualifiedNameNodeList()) {
          this.replaceGetQualifiedName(callNode);
        }

        for (Node callNode : interceptorInfo.getMethodNameNodeList()) {
          this.replaceGetMethodName(callNode);
        }

        for (Node callNode : interceptorInfo.getClassNameNodeList()) {
          this.replaceGetClassName(callNode);
        }

        for (Node callNode : interceptorInfo.getArgumentsNodeList()) {
          this.replaceArguments(callNode);
        }
      }


      public List<Node> getParamList() {
        return Collections.unmodifiableList(this.paramList);
      }


      private void replaceProceed(Node callNode) {
        Node call = NodeUtil.newCallNode(
            NodeUtil.newQualifiedNameNode(convention, PROCEED + "." + "apply"),
            Node.newString(Token.NAME, CONTEXT),
            Node.newString(Token.NAME, ARGS));
        call.copyInformationFromForTree(callNode);
        CampInjectionProcessor.replaceNode(callNode, call);
      }


      private void replaceThis(Node callNode) {
        Node thisNode = Node.newString(Token.NAME, CONTEXT);
        thisNode.copyInformationFromForTree(callNode);
        CampInjectionProcessor.replaceNode(callNode, thisNode);
      }


      private void replaceGetQualifiedName(Node callNode) {
        Node add = new Node(Token.ADD, Node.newString(Token.NAME, CLASS_NAME),
            new Node(
                Token.ADD,
                Node.newString("."), Node.newString(Token.NAME, METHOD_NAME)));
        add.copyInformationFromForTree(callNode);
        CampInjectionProcessor.replaceNode(callNode, add);
      }


      private void replaceGetMethodName(Node callNode) {
        Node name = Node.newString(Token.NAME, METHOD_NAME);
        name.copyInformationFromForTree(callNode);
        CampInjectionProcessor.replaceNode(callNode, name);
      }


      private void replaceGetClassName(Node callNode) {
        Node name = Node.newString(Token.NAME, CLASS_NAME);
        name.copyInformationFromForTree(callNode);
        CampInjectionProcessor.replaceNode(callNode, name);
      }


      private void replaceArguments(Node callNode) {
        Node name = Node.newString(Token.NAME, ARGS);
        name.copyInformationFromForTree(callNode);
        CampInjectionProcessor.replaceNode(callNode, name);
      }
    }
  }


  private final class ModuleConfig {
    private Map<String, Map<String, BindingInfo>> allBindingInfoMap = Maps.newHashMap();

    private Map<String, ClassInfo> clonedMap = Maps.newHashMap();

    private DependenciesResolver dependenciesResolver;

    private ModuleInitializerInfo moduleInitInfo;

    private Map<String, List<InterceptorInfo>> interceptorMap = Maps.newHashMap();
    
    private int variableId = 0;


    public ModuleConfig(ModuleInitializerInfo moduleInitInfo) {
      this.moduleInitInfo = moduleInitInfo;
      this.initBindingMap();
      this.cloneClassInfo();
      this.dependenciesResolver = new DependenciesResolver(this.allBindingInfoMap, this.clonedMap,
          this.moduleInitInfo, compiler);
    }


    private void initBindingMap() {
      for (String moduleName : this.moduleInitInfo.getConfigModuleList()) {
        ModuleInfo moduleInfo = campInjectionInfo.getModuleInfo(moduleName);
        if (moduleInfo != null) {
          this.allBindingInfoMap.put(moduleInfo.getModuleName(), moduleInfo.getBindingInfoMap());
          List<InterceptorInfo> infoList = campInjectionInfo.getInterceptorInfo(moduleName);
          if (infoList != null) {
            List<InterceptorInfo> list = Lists.newArrayList();
            String varName = CampInjectionProcessor.toLowerCase(CampInjectionProcessor
                .getValidVarName(moduleName));
            this.interceptorMap.put(varName, list);
            for (InterceptorInfo interceptorInfo : infoList) {
              interceptorInfo.setModuleName(varName);
              list.add(interceptorInfo);
            }
          }
        }
      }
    }


    private void cloneClassInfo() {
      for (ClassInfo classInfo : campInjectionInfo.getClassInfoMap().values()) {
        ClassInfo newClassInfo = (ClassInfo) classInfo.clone();
        clonedMap.put(newClassInfo.getClassName(), newClassInfo);
      }
      
      for (ClassInfo classInfo : clonedMap.values()) {
        this.bindInterceptorInfo(classInfo);
      }
    }


    private void bindInterceptorInfo(ClassInfo classInfo) {
      if (!classInfo.hasInterceptorFlag()) {
        for (List<InterceptorInfo> interceptorInfoList : this.interceptorMap.values()) {
          for (InterceptorInfo interceptorInfo : interceptorInfoList) {
            if (this.isMatchClass(interceptorInfo, classInfo)) {
              boolean hasMatchedMethod = this.addInterceptorInfoToPrototypeIfMatched(classInfo,
                  interceptorInfo);
              if (hasMatchedMethod) {
                classInfo.setInterceptorFlag();
              }
            }
          }
        }
      }
    }


    private boolean addInterceptorInfoToPrototypeIfMatched(ClassInfo classInfo,
        InterceptorInfo interceptorInfo) {
      boolean hasMatchedMethod = false;
      Map<String, PrototypeInfo> prototypeInfoMap = classInfo.getPrototypeInfoMap();
      for (PrototypeInfo prototypeInfo : prototypeInfoMap.values()) {
        if (this.isMatchMethod(prototypeInfo, interceptorInfo)) {
          if (!prototypeInfo.hasInterceptorInfo(interceptorInfo)) {
            prototypeInfo.addInterceptor(interceptorInfo);
            hasMatchedMethod = true;
          }
        }
      }
      return hasMatchedMethod;
    }


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
    public Map<String, Map<String, BindingInfo>> getAllBindingInfoMap() {
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


    public int getVariableId() {
      int ret = this.variableId;
      this.variableId++;
      return ret;
    }
  }


  private final class ModuleInitializerRewriter implements NodeRewriter {

    private Map<String, Map<String, BindingInfo>> allBindingInfoMap;

    private Map<String, ClassInfo> clonedMap;

    private ModuleInitializerInfo moduleInitializerInfo;

    private DependenciesResolver dependenciesResolver;


    public ModuleInitializerRewriter(ModuleConfig moduleConfig) {
      this.allBindingInfoMap = moduleConfig.getAllBindingInfoMap();
      this.clonedMap = moduleConfig.getClonedMap();
      this.dependenciesResolver = moduleConfig.getDIProcessor();
      this.moduleInitializerInfo = moduleConfig.getModuleInitInfo();
      this.initEagerSingletons();
    }


    private void initEagerSingletons() {
      for (Map<String, BindingInfo> bindingInfoMap : this.allBindingInfoMap.values()) {
        for (BindingInfo bindingInfo : bindingInfoMap.values()) {
          Node exp = bindingInfo.getBindedExpressionNode();
          if (exp.isName() || NodeUtil.isGet(exp)) {
            String qname = exp.getQualifiedName();
            if (!Strings.isNullOrEmpty(qname) && clonedMap.containsKey(qname)) {
              ClassInfo classInfo = clonedMap.get(qname);
              classInfo.setScopeType(bindingInfo.getScopeType());
            }
          }
          this.addEagerSingletonInstances(bindingInfo);
        }
      }
    }


    @Override
    public void rewrite() {
      for (InjectorInfo injectorInfo : this.moduleInitializerInfo.getInjectorInfoList()) {
        new InjectorRewriter(injectorInfo).rewrite();
      }
    }


    private void addEagerSingletonInstances(BindingInfo bindingInfo) {
      if (bindingInfo.isEager()) {
        String name = bindingInfo.getBindedExpressionNode().getQualifiedName();
        if (name != null) {
          ClassInfo info = clonedMap.get(name);
          if (info != null) {
            this.dependenciesResolver.makeInstantiateExpression(info);
          }
        }
      }
    }


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


      private void inliningGetInstanceCall(Node n, String className) {
        ClassInfo info = clonedMap.get(className);
        Node child = null;

        if (info != null) {
          child = dependenciesResolver.makeInstantiateExpression(info);
          child.copyInformationFromForTree(n);
          n.getParent().replaceChild(n, child);
        } else {
          dependenciesResolver.reportClassNotFound(n, className);
        }
      }


      private void inliningGetInstanceByNameCall(Node n, String bindingName) {
        Node child = null;

        for (String moduleName : allBindingInfoMap.keySet()) {
          Map<String, BindingInfo> bindingInfoMap = allBindingInfoMap.get(moduleName);

          if (bindingInfoMap.containsKey(bindingName)) {
            BindingInfo bindingInfo = bindingInfoMap.get(bindingName);

            if (bindingInfo.isProvider()) {
              child = dependenciesResolver.makeProviderCall(bindingInfo, moduleName, false);
              break;
            } else {
              Node expression = bindingInfo.getBindedExpressionNode();
              String name = expression.getQualifiedName();

              ClassInfo info = clonedMap.get(name);

              if (info != null) {
                child = dependenciesResolver.makeInstantiateExpression(info);
                break;
              } else {
                dependenciesResolver.reportClassNotFound(n, name);
              }
            }
          }
        }

        if (child != null) {
          n.getParent().replaceChild(n, child);
        } else {
          dependenciesResolver.reportClassNotFound(n, bindingName);
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
    ModuleConfig moduleConfig = new ModuleConfig(moduleInitializerInfo);
    new ModuleInitializerRewriter(moduleConfig).rewrite();

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
    Node block = CampInjectionProcessor.getFunctionBody(function);

    while (!moduleInitCall.isExprResult() && !moduleInitCall.isVar()
        && !NodeUtil.isStatementBlock(moduleInitCall)) {
      moduleInitCall = moduleInitCall.getParent();
    }

    Node expr = NodeUtil.newExpr(NodeUtil.newCallNode(function));

    expr.copyInformationFromForTree(moduleInitCall);
    CampInjectionProcessor.replaceNode(moduleInitCall, expr);
    compiler.reportCodeChange();

    List<String> copied = Lists.newArrayList();
    copied.addAll(moduleConfigList);
    Collections.reverse(copied);

    for (String config : copied) {
      String varName = CampInjectionProcessor.toLowerCase(CampInjectionProcessor
          .getValidVarName(config));
      Node newCall = new Node(Token.NEW, NodeUtil.newQualifiedNameNode(convention, config));
      Node configureCallNode = NodeUtil.newCallNode(new Node(Token.GETPROP, newCall, Node
          .newString(CampInjectionConsts.MODULE_SETUP_METHOD_NAME)));

      Node var = NodeUtil.newVarNode(varName, configureCallNode);
      var.copyInformationFromForTree(block);

      block.addChildBefore(var, block.getFirstChild());
      compiler.reportCodeChange();
    }
  }
}
