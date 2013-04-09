package com.google.javascript.jscomp;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.javascript.jscomp.CampInjectionConsts.ClassMatchType;
import com.google.javascript.jscomp.CampInjectionConsts.MethodMatchType;
import com.google.javascript.jscomp.CampInjectionInfo.BindingInfo;
import com.google.javascript.jscomp.CampInjectionInfo.ClassInfo;
import com.google.javascript.jscomp.CampInjectionInfo.InterceptorInfo;
import com.google.javascript.jscomp.CampInjectionInfo.ModuleInfo;
import com.google.javascript.jscomp.CampInjectionInfo.ModuleInitializerInfo;
import com.google.javascript.jscomp.CampInjectionInfo.PrototypeInfo;
import com.google.javascript.jscomp.AstTemplateGeneratorFactory.AstTemplateGenerator;
import com.google.javascript.jscomp.AstTemplateGeneratorFactory.GeneratedAst;
import com.google.javascript.jscomp.AstTemplateGeneratorFactory.TemplateGeneratorType;
import com.google.javascript.jscomp.NodeTraversal.AbstractPostOrderCallback;
import com.google.javascript.jscomp.Scope.Var;
import com.google.javascript.rhino.JSDocInfo;
import com.google.javascript.rhino.JSDocInfoBuilder;
import com.google.javascript.rhino.JSTypeExpression;
import com.google.javascript.rhino.Node;
import com.google.javascript.rhino.Token;

/**
 * @author Taketoshi Aono
 * 
 *         Rewrite all DI and interceptors. Rewriter work like follows.
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
 *  //Now BazClass's constructor is rewrited as follows
 *  //function BazClass(foo, jscomp$interceptor$0) {
 *  //  this.jscomp$interceptor$0 = jscomp$interceptor$0;
 *  //  ...
 *  //And BazClass's method 'doSomething' is rewrited like follows.
 *  //BazClass.prototype.doSomething = function() {
 *  //  this.jscomp$interceptor$0 && this.jscomp$interceptor(this, arguments, "BazClass", "doSomething");
 *  </code>
 * 
 */
final class CampInjectionRewriter {

  static final DiagnosticType MESSAGE_CREATE_INSTANCE_TARGET_INVALID = DiagnosticType
      .error(
          "JSC_MSG_CREATE_INSTANCE_TARGET_NOT_VALID.",
          "The argument of camp.injections.injector.createInstance must be a constructor.");

  static final DiagnosticType MESSAGE_BIND_CALL_FIRST_ARGUMENT_IS_INVALID = DiagnosticType
      .error(
          "JSC_MSG_BIND_CALL_FIRST_ARGUMENT_IS_NOT_VALID.",
          "The first argument of function bind must be a string which is key of injection.");

  static final DiagnosticType MESSAGE_BIND_CALL_SECOND_ARGUMENT_IS_INVALID = DiagnosticType
      .error("JSC_MSG_BIND_CALL_SECOND_ARGUMENT_IS_NOT_VALID.",
          "The second argument of bind must be a inject value.");

  static final DiagnosticType MESSAGE_BIND_INTERCEPTOR_FIRST_ARGUMENT_IS_INVALID = DiagnosticType
      .error("JSC_MSG_BIND_PROVIDER_FIRST_ARGUMENT_IS_NOT_VALID.",
          "The first argument of bindProvider must be a one of\n"
              + "  " + CampInjectionConsts.CLASS_MATCHERS_IN_NAMESPACE + "\n"
              + "  " + CampInjectionConsts.CLASS_MATCHERS_IN_SUBNAMESPACE + "\n"
              + "  " + CampInjectionConsts.CLASS_MATCHERS_SUBCLASS_OF + "\n"
              + "  " + CampInjectionConsts.CLASS_MATCHERS_INSTANCE_OF + "\n"
              + "  " + CampInjectionConsts.MATCHERS_ANY);

  static final DiagnosticType MESSAGE_BIND_INTERCEPTOR_SECOND_ARGUMENT_IS_INVALID = DiagnosticType
      .error("JSC_MSG_DEFINE_PROVIDER__SECOND_ARGUMENT_IS_NOT_VALID.",
          "The second argument of bindProvider must be a one of\n"
              + "  " + CampInjectionConsts.METHOD_MATCHER_LIKE + "\n"
              + "  " + CampInjectionConsts.MATCHERS_ANY);

  static final DiagnosticType MESSAGE_BIND_INTERCEPTOR_THIRD_ARGUMENT_IS_INVALID = DiagnosticType
      .error(
          "JSC_MSG_BIND_INTERCEPTOR_THIRD_ARGUMENT_IS_INVALID.",
          "The thrid argument of bindInterceptor must be a function expression which define behavior of the interceptor.");

  static final DiagnosticType MESSAGE_BIND_PROVIDER_FIRST_ARGUMENT_IS_INVALID = DiagnosticType
      .error("JSC_MSG_DEFINE_PROVIDER_FIRST_ARGUMENT_IS_INVALID.",
          "The first argument of bindProvider must be a string or null.");

  static final DiagnosticType MESSAGE_BIND_PROVIDER_SECOND_ARGUMENT_IS_INVALID = DiagnosticType
      .error("JSC_MSG_DEFINE_PROVIDER__SECOND_ARGUMENT_IS_INVALID.",
          "The second argument of bindProvider must be a class constructor.");

  static final DiagnosticType MESSAGE_BIND_PROVIDER_THIRD_ARGUMENTS_IS_INVALID = DiagnosticType
      .error("JSC_MSG_DEFINE_PROVIDER__SECOND_ARGUMENT_IS_INVALID.",
          "The second argument of bindProvider must be a function expression.");

  static final DiagnosticType MESSAGE_ACCESSED_TO_VIRTUAL_METHODS = DiagnosticType
      .error(
          "JSC_MSG_ACCESSED_TO_VIRTUAL_METHODS.",
          "The all methods of {0} can not use as the function object and can not access to method itself.\n"
              + "These are only virtual method because these methods are inlining by compiler.");

  static final DiagnosticType MESSAGE_INVALID_ACCESS_TO_ENTITY = DiagnosticType
      .error(
          "JSC_MSG_INVALID_ACCESS_TO_ENTITY.",
          "The {0} can not use as a object and can not access to itself because that inlined by compiler.");

  static final DiagnosticType MESSAGE_HAS_NO_SUCH_METHOD = DiagnosticType.error(
      "JSC_MSG_JOINPOINT_HAS_NO_SUCH_METHOD.",
      "The {0} has no such method #{1}().\nAvailable methods are\n"
          + "{2}\n");

  static final DiagnosticType MESSAGE_MATCHER_IN_NAMESPACE_ARGUMENT_IS_INVALID = DiagnosticType
      .error(
          "JSC_MSG_MATCHER_IN_NAMESPACE_ARGUMENT_IS_INVALID.",
          "The first argument of camp.injections.Matcher.inNamespace must be a string expression of namespace.");

  static final DiagnosticType MESSAGE_MATCHER_IN_SUBNAMESPACE_ARGUMENT_IS_INVALID = DiagnosticType
      .error(
          "JSC_MSG_MATCHER_IN_SUBNAMESPACE_ARGUMENT_IS_INVALID.",
          "The first argument of camp.injections.Matcher.inSubnamespace must be a string expression of namespace.");

  static final DiagnosticType MESSAGE_MATCHER_INSTANCE_OF_ARGUMENT_IS_INVALID = DiagnosticType
      .error(
          "JSC_MSG_MATCHER_INSTANCE_OF_ARGUMENT_IS_INVALID.",
          "The first argument of camp.injections.Matcher.instanceOf must be a constructor function.");

  static final DiagnosticType MESSAGE_MATCHER_SUBCLASS_OF_ARGUMENT_IS_INVALID = DiagnosticType
      .error(
          "JSC_MSG_MATCHER_SUBCLASS_OF_ARGUMENT_IS_INVALID.",
          "The first argument of camp.injections.Matcher.subclassOf must be a constructor function.");

  static final DiagnosticType MESSAGE_MATCHER_ANY_HAS_NO_ARGUMENT = DiagnosticType
      .error(
          "JSC_MSG_MATCHER_ANY_HAS_NO_ARGUMENT.",
          "camp.injections.Matchers.any has no more than 0 arguments.");

  static final DiagnosticType MESSAGE_MATCHER_LIKE_ARGUMENT_IS_INVALID = DiagnosticType
      .error(
          "JSC_MSG_MATCHER_LIKE_ARGUMENT_IS_INVALID.",
          "The first argument of camp.injections.Matcher.like must be a string expression of the target method name.\n"
              + "The matcher is string expression which treat wilidcard(*) as a special character of the method name.");

  static final DiagnosticType MESSAGE_BINDING_NOT_FOUND = DiagnosticType.warning(
      "JSC_MSG_BINDING_NOT_FOUND",
      "Binding {0} is not found.");

  static final DiagnosticType MESSAGE_CLASS_NOT_FOUND = DiagnosticType.error(
      "MSG_CLASS_NOT_FOUND", "The class {0} is not defined.");

  private static final ImmutableSet<String> INJECTOR_METHOD_SET = new ImmutableSet.Builder<String>()
      .add(CampInjectionConsts.CREATE_INSTANCE)
      .build();

  private static final ImmutableSet<String> BINDER_METHOD_SET = new ImmutableSet.Builder<String>()
      .add(CampInjectionConsts.BIND)
      .add(CampInjectionConsts.BIND_PROVIDER)
      .add(CampInjectionConsts.BIND_INTERCEPTOR)
      .build();

  private static final ImmutableSet<String> METHOD_INVOCATION_METHOD_LIST = new ImmutableSet.Builder<String>()
      .add(CampInjectionConsts.METHOD_INVOCATION_GET_CLASS_NAME)
      .add(CampInjectionConsts.METHOD_INVOCATION_GET_METHOD_NAME)
      .add(CampInjectionConsts.METHOD_INVOCATION_GET_QUALIFIED_NAME)
      .add(CampInjectionConsts.METHOD_INVOCATION_GET_ARGUMENTS)
      .add(CampInjectionConsts.METHOD_INVOCATION_PROCEED)
      .add(CampInjectionConsts.METHOD_INVOCATION_GET_THIS)
      .build();
  
  
  private final AstTemplateGeneratorFactory generatorFactory;

  private final CampInjectionInfo campInjectionInfo;

  private final AbstractCompiler compiler;

  private final CodingConvention convention;

  private int interceptorId = 0;

  private int singletonId = 0;


  public CampInjectionRewriter(AbstractCompiler compiler, CampInjectionInfo campInjectionInfo) {
    this.compiler = compiler;
    this.convention = compiler.getCodingConvention();
    this.campInjectionInfo = campInjectionInfo;
    this.generatorFactory = new AstTemplateGeneratorFactory(this.convention);
  }


  public void rewrite() {
    this.rewriteMarkers();
    this.bindBindingInfo();
    this.rewriteInjections();
  }


  /**
   * Bind all ModuleInfo and BindingInfo by class name.
   */
  private void bindBindingInfo() {
    Map<String, ModuleInfo> moduleInfoMap = campInjectionInfo.getModuleInfoMap();
    for (String className : moduleInfoMap.keySet()) {
      ModuleInfo moduleInfo = moduleInfoMap.get(className);
      Map<String, BindingInfo> bindingInfoMap = campInjectionInfo.getBindingInfoMap(className);
      if (bindingInfoMap != null) {
        moduleInfo.setBindingInfoMap(bindingInfoMap);
      }
    }
  }


  /**
   * Rewrite all modules configure methods.
   */
  private void rewriteMarkers() {
    Map<String, ModuleInfo> moduleInfoMap = campInjectionInfo.getModuleInfoMap();
    for (ModuleInfo moduleInfo : moduleInfoMap.values()) {

      Node function = moduleInfo.getModuleMethodNode();
      if (function != null) {
        Node binder = NodeUtil.getFunctionParameters(function).getFirstChild();

        if (binder != null) {
          Node block = NodeUtil.getFunctionBody(function);
          Node returnNode = new Node(Token.RETURN, new Node(Token.THIS));
          returnNode.copyInformationFromForTree(function);
          block.addChildToBack(returnNode);

          // Traverse 'confgure' method's function body only.
          NodeTraversal.traverseRoots(
              this.compiler,
              Lists.newArrayList(function),
              new RewriteCallback(
                  binder,
                  new BindCallRewriter(moduleInfo.getModuleName(),
                      moduleInfo.isInterceptorRewrited())));

          // This rewriting process is cause the error or warning
          // if the compiler option "--jscomp_error checkTypes",
          // "--jscomp_warnings checkTypes"
          // or "--warning_level VERBOSE" is specified
          // because rewritten method is called with no arguments.
          // So remove all parameters and jsdocs from this method.
          CampInjectionProcessor.getFunctionParameter(function)
              .detachChildren();
          this.removeJSDocInfoFromBindingMethod(function);
          compiler.reportCodeChange();

          moduleInfo.setInterceptorRewrited(true);
        }
      }
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


  /**
   * Inlining the calling of the camp.injections.createInstance.
   */
  private void rewriteInjections() {
    for (ModuleInitializerInfo moduleInitializerInfo : campInjectionInfo.getModuleInitInfoList()) {
      Node function = moduleInitializerInfo.getModuleInitCall();
      Node moduleInitCall = function.getParent();
      Node injector = NodeUtil.getFunctionParameters(function).getFirstChild();

      if (injector != null) {
        List<String> moduleConfigList = moduleInitializerInfo.getConfigModuleList();
        AstTemplateGenerator interceptorGenerator = 
            this.generatorFactory.getGenerator(TemplateGeneratorType.INTERCEPTOR);
        
        NodeTraversal.traverseRoots(this.compiler, Lists.newArrayList(function),
            new RewriteCallback(
                injector, new ModuleInitializerRewriter(moduleConfigList, interceptorGenerator)));

        // This rewriting process is cause the error or warning
        // "--jscomp_error checkTypes", "--jscomp_warnings checkTypes"
        // or "--warning_level VERBOSE" is specified because it change
        // the function into a anonymous function which calling with no
        // arguments.
        // So remove all parameters and jsdocs from this method.
        injector.detachFromParent();
        function.setJSDocInfo(null);

        this.rewriteCall(moduleConfigList, moduleInitCall, function);
        compiler.reportCodeChange();

      }

    }
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


  private abstract class AbstractNodeRewriter {

    private ImmutableSet<String> methodList;

    private String firstArgumentClassName;


    protected AbstractNodeRewriter(String firstArgumentClassName,
        ImmutableSet<String> methodList) {
      this.firstArgumentClassName = firstArgumentClassName;
      this.methodList = methodList;
    }


    public abstract void rewrite(NodeTraversal t, Node n, Node firstArguments);


    public boolean isArgumentReferenceNode(NodeTraversal t, Node n, Node firstChild) {
      Scope scope = t.getScope();
      if (n.isName()) {
        String name = n.getString();
        Var var = scope.getVar(name);
        if (var != null) {
          Node nameNode = var.getNameNode();
          return firstChild.equals(nameNode);
        }
      }
      return false;
    }


    public void checkUsage(NodeTraversal t, Node n, Node firstArgument) {
      this.checkInvalidAccessToEntity(t, n, firstArgument);
      this.checkInvalidAccessToMethod(t, n, firstArgument);
    }


    private String createMethodNameListMessage() {
      String ret = "";
      for (String methodName : this.methodList) {
        ret += "  " + methodName + "\n";
      }
      return ret;
    }


    private void checkInvalidAccessToEntity(NodeTraversal t, Node n, Node firstArgument) {
      Node parent = n.getParent();
      if (n.isName() && !parent.isGetProp() && !parent.isParamList()) {
        if (this.isArgumentReferenceNode(t, n, firstArgument)) {
          t.report(n, MESSAGE_INVALID_ACCESS_TO_ENTITY, this.firstArgumentClassName);
        }
      }
    }


    private void checkInvalidAccessToMethod(NodeTraversal t, Node n, Node firstArgument) {
      Node parent = n.getParent();
      if (n.isGetProp()) {

        if (parent.isCall()) {
          if (parent.getFirstChild().equals(n)) {
            return;
          }
        }

        Node firstChild = n.getFirstChild();
        if (firstChild.isName() && this.isArgumentReferenceNode(t, firstChild, firstArgument)) {
          Node methodNode = firstChild.getNext();
          if (methodNode.isString()) {
            boolean isContains = methodList.contains(methodNode.getString());
            if (isContains) {
              t.report(methodNode,
                  MESSAGE_ACCESSED_TO_VIRTUAL_METHODS,
                  this.firstArgumentClassName);
            } else {
              t.report(methodNode, MESSAGE_HAS_NO_SUCH_METHOD,
                  this.firstArgumentClassName,
                  methodNode.getString(),
                  this.createMethodNameListMessage());
            }
          } else {
            t.report(methodNode, MESSAGE_ACCESSED_TO_VIRTUAL_METHODS, this.firstArgumentClassName);
          }
        }
      }
    }
  }


  private final class BindCallRewriter extends AbstractNodeRewriter {
    String className;


    public BindCallRewriter(String className, boolean interceptorRewrited) {
      super(CampInjectionConsts.BINDER, BINDER_METHOD_SET);
      this.className = className;
    }


    public void rewrite(NodeTraversal t, Node n, Node firstChild) {
      String qualifiedName = n.getQualifiedName();
      String binderName = firstChild.getString();
      if (qualifiedName != null) {
        if (qualifiedName.equals(binderName + "." + CampInjectionConsts.BIND)) {
          this.caseBind(t, n);
        } else if (qualifiedName.equals(binderName + "." + CampInjectionConsts.BIND_PROVIDER)) {
          this.caseProvider(t, n);
        } else if (qualifiedName.equals(binderName + "." + CampInjectionConsts.BIND_INTERCEPTOR)) {
          this.caseInterceptor(t, n);
        }
      }
    }


    private void caseBind(NodeTraversal t, Node n) {
      BindingInfo bindingInfo = new BindingInfo();
      Node bindNameNode = n.getNext();

      if (bindNameNode != null && bindNameNode.isString()) {
        bindingInfo.setName(bindNameNode.getString());
        Node expressionNode = bindNameNode.getNext();
        if (expressionNode != null) {
          bindingInfo.setBindedExpressionNode(expressionNode);
          campInjectionInfo.putBindingInfo(this.className, bindingInfo);
          this.rewriteBinding(t, n, bindNameNode.getString(), expressionNode);
        } else {
          t.report(bindNameNode, MESSAGE_BIND_CALL_SECOND_ARGUMENT_IS_INVALID);
        }
      } else {
        t.report(n, MESSAGE_BIND_CALL_FIRST_ARGUMENT_IS_INVALID);
      }
    }


    private void caseProvider(NodeTraversal t, Node n) {

      Node bindNameNode = n.getNext();
      BindingInfo bindingInfo = null;
      String bindName = null;

      if (bindNameNode != null) {
        if (bindNameNode.isString()) {
          bindName = bindNameNode.getString();
          bindingInfo = new BindingInfo();
          bindingInfo.registerAsProvider(true);
          bindingInfo.setName(bindNameNode.getString());
        }
      } else {
        t.report(n, MESSAGE_BIND_PROVIDER_FIRST_ARGUMENT_IS_INVALID);
      }

      Node classNode = bindNameNode.getNext();

      if (classNode != null && (classNode.isName() || classNode.isGetProp())) {
        String name = classNode.getQualifiedName();
        if (!Strings.isNullOrEmpty(name)) {
          ClassInfo classInfo = campInjectionInfo.getClassInfo(name);
          if (classInfo != null) {
            Node provider = classNode.getNext();
            if (provider != null) {

              classInfo.setProviderNode(provider);
              if (bindingInfo != null) {
                bindingInfo.setProvider(provider);
                bindingInfo.setClassInfo(classInfo);
                campInjectionInfo.putBindingInfo(this.className, bindingInfo);
              }

              // Whether registered as provider or not,
              // register BindingInfo for
              bindingInfo = new BindingInfo();
              bindingInfo.registerAsProvider(true);
              bindingInfo.setName(CampInjectionProcessor.toLowerCase(CampInjectionProcessor
                  .getValidVarName(classInfo.getClassName())));
              bindingInfo.setProvider(provider);
              campInjectionInfo.putBindingInfo(this.className, bindingInfo);
              this.rewriteProvider(provider, n, classInfo.getClassName(), bindName);
            } else {
              t.report(n, MESSAGE_BIND_PROVIDER_THIRD_ARGUMENTS_IS_INVALID);
            }
          } else {
            t.report(n, MESSAGE_CLASS_NOT_FOUND, name);
          }
        } else {
          t.report(n, MESSAGE_BIND_PROVIDER_SECOND_ARGUMENT_IS_INVALID);
        }
      } else {
        t.report(n, MESSAGE_BIND_PROVIDER_SECOND_ARGUMENT_IS_INVALID);
      }
    }


    private void caseInterceptor(NodeTraversal t, Node n) {
      Node classMatcher = n.getNext();
      if (classMatcher != null && classMatcher.isCall()
          && classMatcher.getFirstChild().isGetProp()) {
        Node methodMatcher = classMatcher.getNext();
        if (methodMatcher != null && methodMatcher.isCall()
            && methodMatcher.getFirstChild().isGetProp()) {
          Node interceptor = methodMatcher.getNext();
          if (interceptor != null && interceptor.isFunction()) {
            InterceptorInfo interceptorInfo = this.getInterceptorInfo(t,
                classMatcher.getFirstChild(),
                methodMatcher.getFirstChild(), interceptor);
            this.rewriteInterceptor(t, n, interceptorInfo);
          } else {
            t.report(n, MESSAGE_BIND_INTERCEPTOR_THIRD_ARGUMENT_IS_INVALID);
          }
        } else {
          t.report(n, MESSAGE_BIND_INTERCEPTOR_SECOND_ARGUMENT_IS_INVALID);
        }
      } else {
        t.report(n, MESSAGE_BIND_INTERCEPTOR_FIRST_ARGUMENT_IS_INVALID);
      }
    }


    private InterceptorInfo getInterceptorInfo(NodeTraversal t, Node classMatcher,
        Node methodMatcher, Node interceptor) {
      InterceptorInfo interceptorInfo = new InterceptorInfo();
      String classMatchTypeCallName = classMatcher.getQualifiedName();
      this.setClassMatchType(t, classMatchTypeCallName, classMatcher, interceptorInfo);
      String methodMatchTypeCallName = methodMatcher.getQualifiedName();
      this.setMethodMatchType(t, methodMatchTypeCallName, methodMatcher, interceptorInfo);
      interceptorInfo.setInterceptorNode(interceptor);
      campInjectionInfo.putInterceptorInfo(this.className, interceptorInfo);
      return interceptorInfo;
    }


    private void setClassMatchType(NodeTraversal t, String matchTypeCallName, Node classMatcher,
        InterceptorInfo interceptorInfo) {
      Node node = classMatcher.getNext();
      if (node != null) {
        if (matchTypeCallName.equals(CampInjectionConsts.CLASS_MATCHERS_IN_NAMESPACE)) {
          if (node.isString()) {
            String name = node.getString();
            if (!Strings.isNullOrEmpty(name)) {
              interceptorInfo.setClassMatchType(ClassMatchType.IN_NAMESPACE);
              interceptorInfo.setClassMatcher(name);
            } else {
              t.report(node, MESSAGE_MATCHER_IN_NAMESPACE_ARGUMENT_IS_INVALID);
            }
          } else {
            t.report(node, MESSAGE_MATCHER_IN_NAMESPACE_ARGUMENT_IS_INVALID);
          }
        } else if (matchTypeCallName.equals(CampInjectionConsts.CLASS_MATCHERS_SUBCLASS_OF)) {
          if (NodeUtil.isGet(node) || node.isName()) {
            String name = node.getQualifiedName();
            if (name != null) {
              interceptorInfo.setClassMatchType(ClassMatchType.SUBCLASS_OF);
              interceptorInfo.setClassMatcher(name);
            } else {
              t.report(node, MESSAGE_MATCHER_SUBCLASS_OF_ARGUMENT_IS_INVALID);
            }
          } else {
            t.report(node, MESSAGE_MATCHER_SUBCLASS_OF_ARGUMENT_IS_INVALID);
          }
        } else if (matchTypeCallName.equals(CampInjectionConsts.CLASS_MATCHERS_IN_SUBNAMESPACE)) {
          if (node.isString()) {
            String name = node.getString();
            if (name != null) {
              interceptorInfo.setClassMatchType(ClassMatchType.SUB_NAMESPACE);
              interceptorInfo.setClassMatcher(name);
            } else {
              t.report(node, MESSAGE_MATCHER_IN_SUBNAMESPACE_ARGUMENT_IS_INVALID);
            }
          } else {
            t.report(node, MESSAGE_MATCHER_IN_SUBNAMESPACE_ARGUMENT_IS_INVALID);
          }
        } else if (matchTypeCallName.equals(CampInjectionConsts.CLASS_MATCHERS_INSTANCE_OF)) {
          if (NodeUtil.isGet(node) || node.isName()) {
            String name = node.getQualifiedName();
            if (name != null) {
              interceptorInfo.setClassMatchType(ClassMatchType.INSTANCE_OF);
              interceptorInfo.setClassMatcher(name);
            } else {
              t.report(node, MESSAGE_MATCHER_INSTANCE_OF_ARGUMENT_IS_INVALID);
            }
          } else {
            t.report(node, MESSAGE_MATCHER_INSTANCE_OF_ARGUMENT_IS_INVALID);
          }
        } else if (matchTypeCallName.equals(CampInjectionConsts.MATCHERS_ANY)) {
          t.report(classMatcher, MESSAGE_MATCHER_ANY_HAS_NO_ARGUMENT);
        } else {
          t.report(classMatcher, MESSAGE_BIND_INTERCEPTOR_FIRST_ARGUMENT_IS_INVALID);
        }
      } else {
        if (matchTypeCallName.equals(CampInjectionConsts.MATCHERS_ANY)) {
          interceptorInfo.setClassMatchType(ClassMatchType.ANY);
        } else {
          t.report(classMatcher, MESSAGE_BIND_INTERCEPTOR_FIRST_ARGUMENT_IS_INVALID);
        }
      }
    }


    private void setMethodMatchType(NodeTraversal t, String matchTypeCallName, Node classMatcher,
        InterceptorInfo interceptorInfo) {
      Node node = classMatcher.getNext();
      if (node != null) {
        if (matchTypeCallName.equals(CampInjectionConsts.METHOD_MATCHER_LIKE)) {
          if (node.isString()) {
            String name = node.getString();
            if (!Strings.isNullOrEmpty(name)) {
              interceptorInfo.setMethodMatchType(MethodMatchType.LIKE);
              interceptorInfo.setMethodMatcher(name);
            } else {
              t.report(node, MESSAGE_MATCHER_LIKE_ARGUMENT_IS_INVALID);
            }
          } else {
            t.report(node, MESSAGE_MATCHER_LIKE_ARGUMENT_IS_INVALID);
          }
        } else if (matchTypeCallName.equals(CampInjectionConsts.MATCHERS_ANY)) {
          t.report(classMatcher, MESSAGE_MATCHER_ANY_HAS_NO_ARGUMENT);
        } else {
          t.report(node, MESSAGE_BIND_INTERCEPTOR_SECOND_ARGUMENT_IS_INVALID);
        }
      } else if (matchTypeCallName.equals(CampInjectionConsts.MATCHERS_ANY)) {
        interceptorInfo.setMethodMatchType(MethodMatchType.ANY);
      } else {
        t.report(node, MESSAGE_BIND_INTERCEPTOR_SECOND_ARGUMENT_IS_INVALID);
      }
    }


    private void rewriteBinding(NodeTraversal t, Node n, String bindingName, Node expression) {

      Node tmp = CampInjectionProcessor.getStatementParent(n);
      Preconditions.checkNotNull(tmp);

      if (expression.isName() || expression.isGetProp()) {
        String name = expression.getQualifiedName();
        if (name != null && campInjectionInfo.getClassInfo(name) != null) {
          tmp.detachFromParent();
          return;
        }
      }

      String name = CampInjectionConsts.THIS + "." + bindingName;

      Node assign = new Node(Token.ASSIGN,
          CampInjectionProcessor.newQualifiedNameNode(name),
          expression.cloneTree());

      Node expr = NodeUtil.newExpr(assign);

      expr.copyInformationFromForTree(n);

      tmp.getParent().addChildAfter(expr, tmp);
      tmp.detachFromParent();
    }


    private void rewriteProvider(Node function, Node n, String className, String bindingName) {
      n = CampInjectionProcessor.getStatementParent(n);

      if (bindingName != null) {
        Node expr = new Node(Token.EXPR_RESULT,
            new Node(Token.ASSIGN,
                CampInjectionProcessor.newQualifiedNameNode(CampInjectionConsts.THIS + "."
                    + bindingName),
                function.cloneTree()));
        expr.copyInformationFromForTree(n);
        n.getParent().addChildBefore(expr, n);
      }

      Node provider = bindingName != null ? CampInjectionProcessor.newQualifiedNameNode(
          CampInjectionConsts.THIS
              + "." + bindingName)
          : function.cloneTree();

      Node assignmentNode = new Node(Token.ASSIGN,
          CampInjectionProcessor.newQualifiedNameNode(CampInjectionConsts.THIS
              + "."
              + CampInjectionProcessor.toLowerCase(
                  CampInjectionProcessor.getValidVarName(className))),
          provider);

      Node expr = NodeUtil.newExpr(assignmentNode);
      expr.copyInformationFromForTree(n);

      n.getParent().addChildAfter(expr, n);

      Node typeNode = Node.newString(className);
      JSDocInfoBuilder builder = new JSDocInfoBuilder(false);
      builder.recordReturnType(new JSTypeExpression(typeNode, n.getSourceFileName()));
      JSDocInfo jsDocInfo = builder.build(assignmentNode.getLastChild());
      assignmentNode.getLastChild().setJSDocInfo(jsDocInfo);

      n.detachFromParent();
    }


    private void rewriteInterceptor(NodeTraversal t, Node n, InterceptorInfo interceptorInfo) {
      Node function = interceptorInfo.getInterceptorNode();
      Node tmp = CampInjectionProcessor.getStatementParent(n);
      Preconditions.checkNotNull(tmp);
      String interceptorName = CampInjectionConsts.THIS + "."
          + CampInjectionConsts.INTERCEPTOR_NAME
          + interceptorId;

      Node paramList = function.getFirstChild().getNext();
      Node methodInvocation = paramList.getFirstChild();
      if (methodInvocation != null) {
        MethodInvocationRewriter methodInvocationRewriter = new MethodInvocationRewriter(
            methodInvocation);
        RewriteCallback rewriteCallback = new RewriteCallback(methodInvocation,
            methodInvocationRewriter);
        NodeTraversal.traverseRoots(compiler, Lists.newArrayList(function), rewriteCallback);
        interceptorInfo.setMethodNameAccess(methodInvocationRewriter.isMethodNameAccess());
        interceptorInfo.setClassNameAccess(methodInvocationRewriter.isClassNameAccess());

        paramList.detachChildren();
        for (Node param : methodInvocationRewriter.getParamList()) {
          paramList.addChildToBack(param);
        }
      }

      function.detachFromParent();
      Node expr = NodeUtil.newExpr(new Node(Token.ASSIGN,
          CampInjectionProcessor.newQualifiedNameNode(interceptorName), function));

      expr.copyInformationFromForTree(tmp);
      tmp.getParent().addChildAfter(expr, tmp);
      interceptorInfo.setName(CampInjectionConsts.INTERCEPTOR_NAME + interceptorId);
      interceptorId++;
      tmp.detachFromParent();
    }
  }


  private final class MethodInvocationRewriter extends AbstractNodeRewriter {

    private Node methodInvocation;

    private static final String CONTEXT = "jscomp$methodInvocation$context";

    private static final String ARGS = "jscomp$methodInvocation$args";

    private static final String CLASS_NAME = "jscomp$methodInvocation$className";

    private static final String METHOD_NAME = "jscomp$methodInvocation$methodName";

    private static final String PROCEED = "jscomp$methodInvocation$proceed";

    private boolean classNameAccess = false;

    private boolean methodNameAccess = false;

    private final ImmutableList<Node> paramList = new ImmutableList.Builder<Node>()
        .add(Node.newString(Token.NAME, CONTEXT))
        .add(Node.newString(Token.NAME, ARGS))
        .add(Node.newString(Token.NAME, CLASS_NAME))
        .add(Node.newString(Token.NAME, METHOD_NAME))
        .add(Node.newString(Token.NAME, PROCEED))
        .build();


    public ImmutableList<Node> getParamList() {
      return this.paramList;
    }


    public MethodInvocationRewriter(Node methodInvocation) {
      super(CampInjectionConsts.METHOD_INVOCATION, METHOD_INVOCATION_METHOD_LIST);
      this.methodInvocation = methodInvocation;
      JSDocInfoBuilder jsdocInfoBuilder = new JSDocInfoBuilder(false);
      Node anyType = Node.newString(Token.NAME, "*");
      Node stringType = Node.newString(Token.NAME, "string");
      Node arrayType = Node.newString(Token.NAME, "Array");
      Node functionType = Node.newString(Token.NAME, "Function");

      String sourceFileName = methodInvocation.getSourceFileName();
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

      jsdocInfoBuilder.build(this.methodInvocation.getParent());
    }


    /**
     * @return the classNameAccess
     */
    public boolean isClassNameAccess() {
      return classNameAccess;
    }


    /**
     * @return the methodNameAccess
     */
    public boolean isMethodNameAccess() {
      return methodNameAccess;
    }


    public void rewrite(NodeTraversal t, Node n, Node firstChild) {
      Node methodNode = n.getFirstChild().getNext();
      Node callNode = n.getParent();
      String method = methodNode.getString();
      if (method != null) {
        if (method.equals(CampInjectionConsts.METHOD_INVOCATION_GET_ARGUMENTS)) {
          replaceArguments(callNode);
        } else if (method.equals(CampInjectionConsts.METHOD_INVOCATION_GET_CLASS_NAME)) {
          replaceGetClassName(callNode);
        } else if (method.equals(CampInjectionConsts.METHOD_INVOCATION_GET_METHOD_NAME)) {
          replaceGetMethodName(callNode);
        } else if (method.equals(CampInjectionConsts.METHOD_INVOCATION_GET_QUALIFIED_NAME)) {
          replaceGetQualifiedName(callNode);
        } else if (method.equals(CampInjectionConsts.METHOD_INVOCATION_GET_THIS)) {
          replaceThis(callNode);
        } else if (method.equals(CampInjectionConsts.METHOD_INVOCATION_PROCEED)) {
          replaceProceed(callNode);
        }
      }
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
      this.methodNameAccess = true;
      this.classNameAccess = true;
      Node add = new Node(Token.ADD, Node.newString(Token.NAME, CLASS_NAME),
          new Node(
              Token.ADD,
              Node.newString("."), Node.newString(Token.NAME, METHOD_NAME)));
      add.copyInformationFromForTree(callNode);
      CampInjectionProcessor.replaceNode(callNode, add);
    }


    private void replaceGetMethodName(Node callNode) {
      this.methodNameAccess = true;
      Node name = Node.newString(Token.NAME, METHOD_NAME);
      name.copyInformationFromForTree(callNode);
      CampInjectionProcessor.replaceNode(callNode, name);
    }


    private void replaceGetClassName(Node callNode) {
      this.classNameAccess = true;
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


  private final class ModuleInitializerRewriter extends AbstractNodeRewriter {
    private Map<String, Map<String, BindingInfo>> allBindingInfoMap = Maps.newHashMap();

    private Map<String, List<InterceptorInfo>> interceptorMap = Maps.newHashMap();

    private Map<ClassInfo, String> singletonMap = Maps.newHashMap();

    private Map<String, ClassInfo> clonedMap = Maps.newHashMap();

    private Node currentCreateInstanceCallNode;

    private AstTemplateGenerator interceptorGenerator; 
    
    private int instanceVariableId = 0;


    public ModuleInitializerRewriter(List<String> moduleConfigList,
        AstTemplateGenerator generator) {
      super(CampInjectionConsts.INJECTOR, INJECTOR_METHOD_SET);
      this.interceptorGenerator = generator;
      singletonId++;
      for (String moduleName : moduleConfigList) {
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

      for (ClassInfo classInfo : campInjectionInfo.getClassInfoMap().values()) {
        ClassInfo newClassInfo = (ClassInfo) classInfo.clone();
        clonedMap.put(newClassInfo.getClassName(), newClassInfo);
        this.bindInterceptorInfo(newClassInfo);
      }
    }


    public void rewrite(NodeTraversal t, Node n, Node firstChild) {
      String name = n.getQualifiedName();
      String injector = firstChild.getString();
      if (name.equals(injector + "." + CampInjectionConsts.CREATE_INSTANCE)) {
        Node classNode = n.getNext();
        if (classNode != null) {
          String className = classNode.getQualifiedName();
          if (className != null) {
            this.inliningCreateInstanceCall(t, n, className);
          } else {
            t.report(n, MESSAGE_CREATE_INSTANCE_TARGET_INVALID);
          }
        } else {
          t.report(n, MESSAGE_CREATE_INSTANCE_TARGET_INVALID);
        }
      }
    }


    private void inliningCreateInstanceCall(NodeTraversal t, Node n, String className) {
      Node createInstanceCall = n.getParent();
      this.currentCreateInstanceCallNode = createInstanceCall;
      ClassInfo info = clonedMap.get(className);
      if (info != null) {
        Node child = null;
        if (info.getProviderNode() != null) {
          String varName = CampInjectionProcessor.toLowerCase(CampInjectionProcessor
              .getValidVarName(info.getClassName()));
          for (String cName : this.allBindingInfoMap.keySet()) {
            Map<String, BindingInfo> bindingMap = this.allBindingInfoMap.get(cName);
            if (bindingMap.containsKey(varName)) {
              String targetClassVar = CampInjectionProcessor.toLowerCase(CampInjectionProcessor
                  .getValidVarName(cName));
              child = this.makeNewCallFromProvider(
                  info,
                  NodeUtil.newCallNode(NodeUtil.newQualifiedNameNode(convention, targetClassVar
                      + "." + varName)));
              break;
            }
          }
        } else {
          child = this.makeNewCall(info);
        }

        child.copyInformationFromForTree(createInstanceCall);
        if (child != null) {
          createInstanceCall.getParent().replaceChild(createInstanceCall, child);
        }
      } else {
        t.report(createInstanceCall, MESSAGE_CLASS_NOT_FOUND, className);
      }
    }


    private Node resolveBinding(Node n, ClassInfo classInfo, String bindingName) {

      for (String className : this.allBindingInfoMap.keySet()) {

        String lowerClassName = CampInjectionProcessor.toLowerCase(CampInjectionProcessor
            .getValidVarName(className));

        Map<String, BindingInfo> bindingMap = this.allBindingInfoMap.get(className);

        if (bindingMap.containsKey(bindingName)) {
          BindingInfo bindingInfo = bindingMap.get(bindingName);
          Node entity = bindingInfo.getBindedExpressionNode();
          ClassInfo info = null;
          String name;
          Node ret = null;

          if (bindingInfo.getProviderNode() == null) {
            Preconditions.checkState(entity != null, "In module " + className + " binding "
                + bindingName + " is not found.");
            if (entity.isGetProp() && (name = entity.getQualifiedName()) != null) {
              info = clonedMap.get(name);
            } else if (entity.isName() && (name = entity.getString()) != null) {
              info = clonedMap.get(name);
            }
          }

          if (info != null) {
            if (info.getProviderNode() != null) {
              String varName = CampInjectionProcessor.toLowerCase(CampInjectionProcessor
                  .getValidVarName(info.getClassName()));

              Node callTargetNode = NodeUtil.newQualifiedNameNode(convention, lowerClassName
                  + "." + varName);
              Node callNode = NodeUtil.newCallNode(callTargetNode);

              ret = this.makeNewCallFromProvider(info, callNode);

            } else {
              ret = this.makeNewCall(info);
            }
          } else {
            if (bindingInfo.isRegisteredAsProvider()) {
              ret = this.makeNewCallFromProvider(
                  bindingInfo.getClassInfo(),
                  NodeUtil.newCallNode(NodeUtil.newQualifiedNameNode(convention, lowerClassName
                      + "."
                      + bindingInfo.getName())));
            } else {
              ret = NodeUtil.newQualifiedNameNode(convention, lowerClassName + "."
                  + bindingName);
            }
          }
          return ret;
        }
      }

      report(n, MESSAGE_BINDING_NOT_FOUND, bindingName);
      return new Node(Token.NULL);
    }


    private void bindInterceptorInfo(ClassInfo classInfo) {
      if (!classInfo.hasInterceptorFlag()) {
        for (List<InterceptorInfo> interceptorInfoSet : this.interceptorMap.values()) {
          for (InterceptorInfo interceptorInfo : interceptorInfoSet) {

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


    private GeneratedAst makeEnhancedConstructor(ClassInfo classInfo) {
      if (!classInfo.isConstructorExtended()) {
        GeneratedAst result = interceptorGenerator.generate(classInfo);
        return result;
      }
      return null;
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


    private Node makeNewCallFromProvider(ClassInfo classInfo, Node call) {
      Node function = classInfo.getProviderNode();
      Node paramList = function.getFirstChild().getNext();
      for (Node param : paramList.children()) {
        Node binding = this.resolveBinding(param, classInfo, param.getString());
        binding.copyInformationFromForTree(param);
        call.addChildToBack(binding);
      }

      return call;
    }


    private Node makeNewCall(ClassInfo classInfo) {
      Node newCall;
      if (classInfo.hasInterceptorFlag()) {
        GeneratedAst result = makeEnhancedConstructor(classInfo);
        if (result != null) {
          Node n = CampInjectionProcessor.getStatementTopNode(this.currentCreateInstanceCallNode);
          if (n != null) {
            Node block = result.getBlock();
            n.getParent().addChildBefore(block, n);
            NodeUtil.tryMergeBlock(block);
          }
        }
      }

      if (classInfo.isSingleton()) {
        newCall = this.makeSingleton(classInfo);
      } else {
        newCall = this.makeSimpleNewCall(classInfo);
        if (classInfo.getSetterList().size() > 0) {
          return this.makeNewCallAndMethodCallExpressionNode(newCall, classInfo);
        }
      }
      return newCall;
    }


    private Node makeSimpleNewCall(ClassInfo classInfo) {
      Node newCall = new Node(Token.NEW, NodeUtil.newQualifiedNameNode(convention,
          classInfo.getClassName()));
      for (String param : classInfo.getParamList()) {
        newCall
            .addChildToBack(this.resolveBinding(classInfo.getConstructorNode(), classInfo, param));
      }
      return newCall;
    }


    private Node makeSingleton(ClassInfo classInfo) {
      if (!this.singletonMap.containsKey(classInfo)) {
        Node singletonCall = classInfo.getSingletonCallNode();
        String name = CampInjectionConsts.GET_INJECTIED_INSTANCE + singletonId;
        Node getInstanceMirror = Node.newString(name);
        Node className = singletonCall.getFirstChild().getNext().cloneTree();
        Node instanceHolder = new Node(Token.GETPROP, className.cloneTree(),
            Node.newString(CampInjectionConsts.INJECTED_INSTANCE));
        Node newCall = this.makeSimpleNewCall(classInfo);

        Node instaniationBlock = new Node(Token.BLOCK);

        

        if (classInfo.getSetterList() != null) {
          newCall = this.makeNewCallCommaExpression(newCall, instanceHolder, classInfo);
        }
        
        instaniationBlock.addChildToFront(new Node(Token.EXPR_RESULT, new Node(
            Token.ASSIGN, instanceHolder.cloneTree(), newCall)));

        Node paramList = new Node(Token.PARAM_LIST);

        for (String moduleName : this.allBindingInfoMap.keySet()) {
          paramList
              .addChildToBack(Node.newString(Token.NAME,
                  CampInjectionProcessor.toLowerCase(CampInjectionProcessor
                      .getValidVarName(moduleName))));
        }

        Node block = new Node(Token.BLOCK,
            new Node(Token.IF, new Node(Token.NOT, instanceHolder.cloneTree()),
                instaniationBlock), new Node(Token.RETURN, instanceHolder.cloneTree()));

        Node expr = new Node(Token.EXPR_RESULT,
            new Node(Token.ASSIGN,
                new Node(Token.GETPROP,
                    className, getInstanceMirror),
                new Node(Token.FUNCTION, Node.newString(Token.NAME, ""), paramList, block)));

        Node function = expr.getFirstChild().getLastChild();

        Node tmp = CampInjectionProcessor.getStatementParent(singletonCall);
        Preconditions.checkNotNull(tmp);

        Map<String, Node> injectedSingletonCallMap = classInfo.getInjectedSingletonCallMap();
        boolean notCreate = false;
        for (String singletonCallName : injectedSingletonCallMap.keySet()) {
          Node node = injectedSingletonCallMap.get(singletonCallName);
          if (node.isEquivalentTo(function)) {
            notCreate = true;
            this.singletonMap.put(classInfo, singletonCallName);
          }
        }

        if (!notCreate) {
          tmp.getParent().addChildAfter(expr, tmp);
          classInfo.putInjectedSingletonCall(name, function);
          this.singletonMap.put(classInfo, name);
          if (classInfo.getAliasPoint() != null) {
            Node aliasPoint = classInfo.getAliasPoint();
            Node top = CampInjectionProcessor.getStatementTopNode(aliasPoint);
            if (top != null) {
              Node aliasProp = NodeUtil.newQualifiedNameNode(convention, classInfo.getClassName()
                  + "." + name);
              Node assignment = new Node(Token.ASSIGN, aliasProp, new Node(Token.GETPROP,
                  className.cloneTree(), getInstanceMirror.cloneTree()));
              Node aliasExpr = NodeUtil.newExpr(assignment);
              aliasExpr.copyInformationFromForTree(top);
              top.getParent().addChildAfter(aliasExpr, top);
            }
          }
        }
      }

      Node callTargetNode = NodeUtil.newQualifiedNameNode(convention, classInfo.getClassName()
          + "." + this.singletonMap.get(classInfo));
      Node callNode = NodeUtil.newCallNode(callTargetNode);

      for (String moduleName : this.allBindingInfoMap.keySet()) {
        callNode
            .addChildToBack(Node.newString(Token.NAME,
                CampInjectionProcessor.toLowerCase(CampInjectionProcessor
                    .getValidVarName(moduleName))));
      }
      return callNode;
    }


    private Node makeNewCallAndMethodCallExpressionNode(Node newCall, ClassInfo classInfo) {
      Node top = CampInjectionProcessor.getStatementTopNode(this.currentCreateInstanceCallNode);
      Node instanceVar = null;
     
      Preconditions.checkNotNull(top);      
      if (top.isExprResult() && top.getFirstChild().isAssign()) {
        instanceVar = top.getFirstChild();
      } else {
        Node var = new Node(Token.VAR, Node.newString(Token.NAME, "instance$" + this.instanceVariableId));
        var.copyInformationFromForTree(top);
        top.getParent().addChildBefore(var, top);
        instanceVar = var.getFirstChild().cloneNode();
        this.instanceVariableId++;
      }
      
      Node commaExp = this.makeNewCallCommaExpression(newCall, instanceVar, classInfo);      
      return commaExp;
    }


    private Node makeNewCallCommaExpression(Node newCall, Node instanceVar, ClassInfo classInfo) {
      instanceVar = instanceVar.cloneTree();
      List<Node> expList = Lists.newArrayList(new Node(Token.ASSIGN, instanceVar.cloneTree(), newCall));
      for (String setterName : classInfo.getSetterList()) {
        PrototypeInfo prototypeInfo = classInfo.getPrototypeInfo(setterName);
        if (prototypeInfo != null) {
          Node setterCall = NodeUtil.newCallNode(NodeUtil.newQualifiedNameNode(convention,
              instanceVar.getQualifiedName() + "."
                  + setterName));

          for (String param : prototypeInfo.getParamList()) {
            Node binding = this.resolveBinding(prototypeInfo.getFunction(), classInfo,
                param);
            binding.copyInformationFromForTree(prototypeInfo.getFunction());
            setterCall.addChildToBack(binding);
          }

          expList.add(setterCall);
        }
      }
      expList.add(instanceVar.cloneTree());
      return CampInjectionProcessor.newCommaExpression(expList);
    }
  }


  private final class RewriteCallback extends AbstractPostOrderCallback {
    private Node firstArgument;

    private AbstractNodeRewriter processor;


    public RewriteCallback(
        Node firstArgument,
        AbstractNodeRewriter processor) {
      this.firstArgument = firstArgument;
      this.processor = processor;
    }


    @Override
    public void visit(NodeTraversal t, Node n, Node parent) {
      this.processor.checkUsage(t, n, this.firstArgument);
      if (parent.isCall()) {
        if (n.isGetProp() && n.getFirstChild().isName()) {
          if (processor.isArgumentReferenceNode(t, n.getFirstChild(), this.firstArgument)) {
            processor.rewrite(t, n, this.firstArgument);
          }
        }
      }
    }
  }


  private void report(Node n, DiagnosticType message, String... arguments) {
    JSError error = JSError.make(n.getSourceFileName(),
        n, message, arguments);
    compiler.report(error);
  }
}
