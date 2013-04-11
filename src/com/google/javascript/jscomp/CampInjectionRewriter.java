package com.google.javascript.jscomp;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;

import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.javascript.jscomp.AstBuilders.CallBuilder;
import com.google.javascript.jscomp.AstBuilders.FunctionBuilder;
import com.google.javascript.jscomp.CampInjectionConsts.ClassMatchType;
import com.google.javascript.jscomp.CampInjectionConsts.MethodMatchType;
import com.google.javascript.jscomp.CampInjectionInfo.BindingInfo;
import com.google.javascript.jscomp.CampInjectionInfo.BindingType;
import com.google.javascript.jscomp.CampInjectionInfo.ClassInfo;
import com.google.javascript.jscomp.CampInjectionInfo.InterceptorInfo;
import com.google.javascript.jscomp.CampInjectionInfo.ModuleInfo;
import com.google.javascript.jscomp.CampInjectionInfo.ModuleInitializerInfo;
import com.google.javascript.jscomp.CampInjectionInfo.PrototypeInfo;
import com.google.javascript.jscomp.AstTemplateGeneratorFactory.AstTemplateGenerator;
import com.google.javascript.jscomp.AstTemplateGeneratorFactory.GeneratedAst;
import com.google.javascript.jscomp.AstTemplateGeneratorFactory.TemplateGeneratorType;
import com.google.javascript.jscomp.CampInjectionInfo.ScopeType;
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

  static final DiagnosticType MESSAGE_GET_INSTANCE_TARGET_INVALID = DiagnosticType
      .error(
          "JSC_MSG_CREATE_INSTANCE_TARGET_NOT_VALID.",
          String.format("The argument of %s must be a constructor.",
              CampInjectionConsts.GET_INSTANCE));

  static final DiagnosticType MESSAGE_GET_INSTANCE_BY_NAME_TARGET_INVALID = DiagnosticType
      .error(
          "JSC_MSG_CREATE_INSTANCE_TARGET_BY_NAME_NOT_VALID.",
          String.format("The argument of %s must be a string.",
              CampInjectionConsts.GET_INSTANCE_BY_NAME));

  static final DiagnosticType MESSAGE_BIND_CALL_FIRST_ARGUMENT_IS_INVALID = DiagnosticType
      .error(
          "JSC_MSG_BIND_CALL_FIRST_ARGUMENT_IS_NOT_VALID.",
          "The first argument of function bind must be a string which is key of injection.");

  private static final ImmutableMap<BindingType, String> BINDING_ERROR_MAP = new ImmutableMap.Builder<BindingType, String>()
      .put(
          BindingType.TO,
          String.format("The argument of method %s must be a constructor function.",
              CampInjectionInfo.bindingTypeMap.get(BindingType.TO)))
      .put(
          BindingType.TO_INSTANCE,
          String.format("The argument of method %s must be a expression.",
              CampInjectionInfo.bindingTypeMap.get(BindingType.TO_INSTANCE)))
      .put(
          BindingType.TO_PROVIDER,
          String.format("The argument of method %s must be a function expression.",
              CampInjectionInfo.bindingTypeMap.get(BindingType.TO_PROVIDER)))
      .build();

  static final DiagnosticType MESSAGE_BIND_CALL_SECOND_ARGUMENT_IS_INVALID = DiagnosticType
      .error("JSC_MSG_BIND_CALL_SECOND_ARGUMENT_IS_NOT_VALID.", "{0}");

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
          "The first argument of bindProvider must be a function expression.");

  static final DiagnosticType MESSAGE_BINDING_SCOPE_IS_INVALID = DiagnosticType
      .error("JSC_MSG_BINDING_SCOPE_IS_INVALID.",
          "The binding scope is specifiable only if binding method is 'to'.");

  static final DiagnosticType MESSAGE_BINDING_SCOPE_TYPE_IS_INVALID = DiagnosticType
      .error("JSC_MSG_BINDING_SCOPE_TYPE_IS_INVALID.",
          "The argument of camp.injections.Binder.as must be a one of\n" +
              CampInjectionInfo.getScopeTypeString());

  static final DiagnosticType MESSAGE_BINDER_BIND_HAS_NO_SUCH_METHOD = DiagnosticType
      .error("JSC_MSG_BINDER_BIND_HAS_NO_SUCH_METHOD.",
          "The camp.injections.Binder.bind has no such method {0}.");

  static final DiagnosticType MESSAGE_BINDER_BIND_CHAIN_HAS_NO_SUCH_METHOD = DiagnosticType
      .error("JSC_MSG_BINDER_BIND_CHAIN_HAS_NO_SUCH_METHOD.",
          "The camp.injections.Binder.bind.{0} has no such method {1}.");
  
  static final DiagnosticType MESSAGE_BINDING_IS_NOT_A_PROVIDER = DiagnosticType
      .warning("JSC_MSG_BINDING_IS_NOT_A_PROVIDER.",
          "The parameter is specified as provider but binding {0} is not a provider.");

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
      .add(CampInjectionConsts.GET_INSTANCE)
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
                injector, new ModuleInitializerRewriter(function, moduleConfigList,
                    interceptorGenerator)));

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


    private final class BindingBuilder {
      private BindingInfo bindingInfo;

      private NodeTraversal nodeTraversal;

      private Node node;

      private boolean hasError = false;


      public BindingBuilder(String bindingName, NodeTraversal t, Node n) {
        this.bindingInfo = new BindingInfo(bindingName);
        this.nodeTraversal = t;
        this.node = n;
      }


      public BindingInfo build() {
        this.buildBindingInfo(this.node);
        return this.hasError ? null : this.bindingInfo;
      }


      private void buildBindingInfo(Node n) {
        Node maybeCall = n.getParent();
        Node maybeGetProp = maybeCall.getParent();
        if (maybeCall.isCall() && maybeGetProp.isGetProp()) {
          Node methodNode = maybeCall.getNext();
          if (methodNode != null) {
            maybeCall = maybeGetProp.getParent();
            if (methodNode.isString()) {
              String methodName = methodNode.getString();
              if (maybeCall.isCall())
                this.buildChain(methodName, maybeCall);
            }
          }
        }
      }


      private void buildChain(String methodName, Node call) {
        BindingType bindingType = CampInjectionInfo.bindingTypeMap.get(methodName);
        if (bindingType != null) {
          Node bindingTarget = call.getFirstChild().getNext();
          if (bindingTarget != null) {
            this.bindingInfo.setBindedExpressionNode(bindingTarget);
            this.bindingInfo.setBindingType(bindingType);
            this.buildBindingInfo(call.getFirstChild());
          } else {
            String message = BINDING_ERROR_MAP.get(bindingType);
            this.nodeTraversal.report(call, MESSAGE_BIND_CALL_SECOND_ARGUMENT_IS_INVALID, message);
          }
        } else if (methodName.equals("as")) {
          if (bindingInfo.getBindingType() == BindingType.TO) {
            Node scopeTypeNode = call.getFirstChild().getNext();
            if (scopeTypeNode != null && scopeTypeNode.isGetProp()) {
              String qualifiedName = scopeTypeNode.getQualifiedName();
              ScopeType scopeType = CampInjectionInfo.scopeTypeMap.get(qualifiedName);
              if (scopeType != null) {
                this.bindingInfo.setScopeType(scopeType);
              } else {
                this.nodeTraversal.report(call, MESSAGE_BINDING_SCOPE_TYPE_IS_INVALID);
              }
            } else {
              this.nodeTraversal.report(call, MESSAGE_BINDING_SCOPE_TYPE_IS_INVALID);
            }
          } else {
            this.nodeTraversal.report(call, MESSAGE_BINDING_SCOPE_IS_INVALID);
          }
        } else {
          if (bindingInfo.getBindingType() == null) {
            this.nodeTraversal.report(call, MESSAGE_BINDER_BIND_HAS_NO_SUCH_METHOD, methodName);
          } else {
            String bindChainName = CampInjectionInfo.bindingTypeMap.inverse().get(
                bindingInfo.getBindingType());
            this.nodeTraversal.report(call, MESSAGE_BINDER_BIND_CHAIN_HAS_NO_SUCH_METHOD,
                bindChainName, methodName);
          }
        }
      }
    }


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
        } else if (qualifiedName.equals(binderName + "." + CampInjectionConsts.BIND_INTERCEPTOR)) {
          this.caseInterceptor(t, n);
        }
      }
    }


    private void caseBind(NodeTraversal t, Node n) {
      Node bindNameNode = n.getNext();
      if (bindNameNode != null && bindNameNode.isString()) {
        String bindingName = bindNameNode.getString();
        BindingInfo bindingInfo = new BindingBuilder(bindingName, t, n).build();
        if (bindingInfo != null) {
          campInjectionInfo.putBindingInfo(this.className, bindingInfo);
          this.rewriteBinding(t, n, bindNameNode.getString(), bindingInfo);
        }
      } else {
        t.report(n, MESSAGE_BIND_CALL_FIRST_ARGUMENT_IS_INVALID);
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


    private void rewriteBinding(NodeTraversal t, Node n, String bindingName, BindingInfo bindingInfo) {
      Node expression = bindingInfo.getBindedExpressionNode();
      Node tmp = CampInjectionProcessor.getStatementTopNode(n);
      Preconditions.checkNotNull(tmp);

      switch (bindingInfo.getBindingType()) {
      case TO: {
        if (expression.isName() || expression.isGetProp()) {
          String name = expression.getQualifiedName();
          if (name != null && campInjectionInfo.getClassInfo(name) != null) {
            tmp.detachFromParent();
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
      }
      }
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

    private Map<String, ClassInfo> clonedMap = Maps.newHashMap();

    private Node currentCreateInstanceCallNode;

    private AstTemplateGenerator interceptorGenerator;

    private int instanceVariableId = 0;

    private Node scope;


    private final class SingletonBuilder {
      private Node makeSingletonVariable() {
        Node instanceVar = Node.newString(Token.NAME, "singletonInstance" + singletonId);
        singletonId++;
        return instanceVar;
      }


      public Node makeLazySingleton(ClassInfo classInfo) {
        Node instanceVar;
        if (classInfo.getSingletonVariable() == null) {

          instanceVar = this.makeSingletonVariable();
          Node var = new Node(Token.VAR, instanceVar);
          Node top = CampInjectionProcessor.getStatementTopNode(currentCreateInstanceCallNode);
          Preconditions.checkNotNull(top);

          top.getParent().addChildBefore(var, top);
          classInfo.setSingletonVariable(instanceVar);

        } else {
          instanceVar = classInfo.getSingletonVariable();
        }

        Node newCall = makeSimpleNewCall(classInfo);

        if (classInfo.getSetterList() != null) {
          newCall = makeNewCallCommaExpression(newCall, instanceVar, classInfo);
        }

        Node hook = new Node(Token.HOOK, instanceVar.cloneNode(), instanceVar.cloneNode());
        hook.addChildToBack(newCall);
        return hook;
      }


      public Node makeEagerSingleton(ClassInfo classInfo) {
        if (classInfo.getSingletonVariable() == null) {
          Node instanceVar = this.makeSingletonVariable();
          Node var = new Node(Token.VAR, instanceVar);
          Node block = NodeUtil.getFunctionBody(scope);
          block.addChildToFront(var);
          classInfo.setSingletonVariable(instanceVar);

          Node newCall = makeSimpleNewCall(classInfo);

          if (classInfo.getSetterList() != null) {
            newCall = makeNewCallCommaExpression(newCall, instanceVar, classInfo);
          }

          Node assign = new Node(Token.ASSIGN, instanceVar.cloneNode(), newCall);
          Node expr = NodeUtil.newExpr(assign);
          expr.copyInformationFromForTree(block);
          block.addChildAfter(expr, block.getFirstChild());
          return instanceVar.cloneNode();
        } else {
          return classInfo.getSingletonVariable().cloneNode();
        }
      }
    }


    public ModuleInitializerRewriter(Node scope, List<String> moduleConfigList,
        AstTemplateGenerator generator) {
      super(CampInjectionConsts.INJECTOR, INJECTOR_METHOD_SET);
      this.interceptorGenerator = generator;
      this.scope = scope;

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

      for (Map<String, BindingInfo> bindingInfoMap : this.allBindingInfoMap.values()) {
        this.addEagerSingletonInstances(bindingInfoMap);
        for (BindingInfo bindingInfo : bindingInfoMap.values()) {
          Node exp = bindingInfo.getBindedExpressionNode();
          if (exp.isName() || NodeUtil.isGet(exp)) {
            String qname = exp.getQualifiedName();
            if (!Strings.isNullOrEmpty(qname) && clonedMap.containsKey(qname)) {
              ClassInfo classInfo = clonedMap.get(qname);
              classInfo.setScopeType(bindingInfo.getScopeType());
            }
          }
        }
      }
    }


    private void addEagerSingletonInstances(Map<String, BindingInfo> bindingInfoMap) {
      for (BindingInfo bindingInfo : bindingInfoMap.values()) {
        if (bindingInfo.isEager()) {
          String name = bindingInfo.getBindedExpressionNode().getQualifiedName();
          if (name != null) {
            ClassInfo info = clonedMap.get(name);
            if (info != null) {
              SingletonBuilder builder = new SingletonBuilder();
              builder.makeEagerSingleton(info);
            }
          }
        }
      }
    }


    public void rewrite(NodeTraversal t, Node n, Node firstChild) {
      String name = n.getQualifiedName();
      String injector = firstChild.getString();
      if (name.equals(injector + "." + CampInjectionConsts.GET_INSTANCE)) {
        Node classNode = n.getNext();
        if (classNode != null) {
          String className = classNode.getQualifiedName();
          if (className != null) {
            this.inliningGetInstanceCall(t, n, className);
          } else {
            t.report(n, MESSAGE_GET_INSTANCE_TARGET_INVALID);
          }
        } else {
          t.report(n, MESSAGE_GET_INSTANCE_TARGET_INVALID);
        }
      } else if (name.equals(injector + "." + CampInjectionConsts.GET_INSTANCE_BY_NAME)) {
        Node stringNode = n.getNext();
        if (stringNode != null && stringNode.isString()) {
          String bindingName = stringNode.getString();
          if (bindingName != null) {
            this.inliningGetInstanceByNameCall(t, n, bindingName);
          } else {
            t.report(n, MESSAGE_GET_INSTANCE_TARGET_INVALID);
          }
        } else {
          t.report(n, MESSAGE_GET_INSTANCE_TARGET_INVALID);
        }
      }
    }


    private void inliningGetInstanceCall(NodeTraversal t, Node n, String className) {
      Node createInstanceCall = n.getParent();
      this.currentCreateInstanceCallNode = createInstanceCall;
      ClassInfo info = clonedMap.get(className);
      Node child = null;

      if (info != null) {
        child = this.makeNewCall(info);
        child.copyInformationFromForTree(createInstanceCall);
        createInstanceCall.getParent().replaceChild(createInstanceCall, child);
      } else {
        t.report(createInstanceCall, MESSAGE_CLASS_NOT_FOUND, className);
      }
    }


    private void inliningGetInstanceByNameCall(NodeTraversal t, Node n, String bindingName) {
      Node createInstanceCall = n.getParent();
      this.currentCreateInstanceCallNode = createInstanceCall;
      Node child = null;

      for (String moduleName : this.allBindingInfoMap.keySet()) {
        Map<String, BindingInfo> bindingInfoMap = this.allBindingInfoMap.get(moduleName);

        if (bindingInfoMap.containsKey(bindingName)) {
          BindingInfo bindingInfo = bindingInfoMap.get(bindingName);

          if (bindingInfo.isProvider()) {
            child = this.makeProviderCall(bindingInfo, moduleName, false);
            break;
          } else {
            Node expression = bindingInfo.getBindedExpressionNode();
            String name = expression.getQualifiedName();

            ClassInfo info = clonedMap.get(name);

            if (info != null) {
              child = this.makeNewCall(info);
              break;
            } else {
              t.report(createInstanceCall, MESSAGE_CLASS_NOT_FOUND, name);
            }
          }
        }
      }

      if (child != null) {
        createInstanceCall.getParent().replaceChild(createInstanceCall, child);
      } else {
        t.report(createInstanceCall, MESSAGE_CLASS_NOT_FOUND, bindingName);
      }
    }


    private Node resolveBinding(Node n, String bindingName) {

      boolean isPassProviderObject = false;
      int index = bindingName.indexOf("Provider");

      if (index > -1) {
        isPassProviderObject = true;
        bindingName = bindingName.substring(0, index);
      }

      for (String className : this.allBindingInfoMap.keySet()) {
        Map<String, BindingInfo> bindingMap = this.allBindingInfoMap.get(className);

        if (bindingMap.containsKey(bindingName)) {
          BindingInfo bindingInfo = bindingMap.get(bindingName);

          switch (bindingInfo.getBindingType()) {
          
          case TO:
            if (isPassProviderObject) {
              report(n, MESSAGE_BINDING_IS_NOT_A_PROVIDER, bindingName);
            }
            
            String name = bindingInfo.getBindedExpressionNode().getQualifiedName();
            ClassInfo info = clonedMap.get(name);
            
            if (info != null) {
              return this.makeNewCall(info);
            }
            
          case TO_PROVIDER:
            return this.makeProviderCall(bindingInfo, className, isPassProviderObject);
            
          case TO_INSTANCE:
            if (isPassProviderObject) {
              report(n, MESSAGE_BINDING_IS_NOT_A_PROVIDER, bindingName);
            }
            
            String lowerClassName = CampInjectionProcessor.toLowerCase(CampInjectionProcessor
                .getValidVarName(className));
            
            return NodeUtil.newQualifiedNameNode(convention, lowerClassName + "." + bindingName);
          }
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


    private Node makeProviderCall(BindingInfo bindingInfo, String moduleName,
        boolean isPassProviderObject) {
      String lowerClassName = CampInjectionProcessor.toLowerCase(CampInjectionProcessor
          .getValidVarName(moduleName));
      Node nameNode = NodeUtil.newQualifiedNameNode(convention, lowerClassName + "."
          + bindingInfo.getName());
      Node call = NodeUtil.newCallNode(nameNode);
      Node function = bindingInfo.getBindedExpressionNode();
      Node paramList = function.getFirstChild().getNext();

      addCallParameters(paramList, function, call);

      if (isPassProviderObject) {
        return new FunctionBuilder().setBody(new Node(Token.RETURN, call)).build();
      } else {
        return call;
      }
    }


    private Node makeNewCall(ClassInfo classInfo) {
      Node newCall = null;
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
        return this.makeSingleton(classInfo);
      } else {
        newCall = this.makeSimpleNewCall(classInfo);
        if (classInfo.getSetterList().size() > 0) {
          return this.makeNewCallAndMethodCallExpressionNode(newCall, classInfo);
        }
        return newCall;
      }
    }


    private Node makeSimpleNewCall(ClassInfo classInfo) {
      Node newCall = new CallBuilder(true).setCallTarget(classInfo.getClassName()).build();
      this.addCallParameters(classInfo.getParamList(), classInfo.getConstructorNode(), newCall);
      return newCall;
    }


    private Node makeSingleton(ClassInfo classInfo) {
      SingletonBuilder builder = new SingletonBuilder();
      if (classInfo.isEager()) {
        return builder.makeEagerSingleton(classInfo);
      } else {
        return builder.makeLazySingleton(classInfo);
      }
    }


    private Node makeNewCallAndMethodCallExpressionNode(Node newCall, ClassInfo classInfo) {
      Node top = CampInjectionProcessor.getStatementTopNode(this.currentCreateInstanceCallNode);
      Node instanceVar = null;

      Preconditions.checkNotNull(top);
      if (top.isExprResult() && top.getFirstChild().isAssign()) {
        instanceVar = top.getFirstChild();
      } else {
        Node var = new Node(Token.VAR, Node.newString(Token.NAME, "instance$"
            + this.instanceVariableId));
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
      List<Node> expList = Lists.newArrayList(new Node(Token.ASSIGN, instanceVar.cloneTree(),
          newCall));
      for (String setterName : classInfo.getSetterList()) {
        PrototypeInfo prototypeInfo = classInfo.getPrototypeInfo(setterName);
        if (prototypeInfo != null) {
          Node setterCall = NodeUtil.newCallNode(NodeUtil.newQualifiedNameNode(convention,
              instanceVar.getQualifiedName() + "."
                  + setterName));

          for (String param : prototypeInfo.getParamList()) {
            Node binding = this.resolveBinding(prototypeInfo.getFunction(), param);
            binding.copyInformationFromForTree(prototypeInfo.getFunction());
            setterCall.addChildToBack(binding);
          }

          expList.add(setterCall);
        }
      }
      expList.add(instanceVar.cloneTree());
      return CampInjectionProcessor.newCommaExpression(expList);
    }


    private void addCallParameters(List<String> paramList, Node n, Node call) {
      for (String param : paramList) {
        call
            .addChildToBack(this.resolveBinding(n, param));
      }
    }


    private void addCallParameters(Node paramList, Node n, Node call) {
      Preconditions.checkArgument(paramList.isParamList());
      List<String> paramNameList = Lists.newArrayList();
      for (Node param : paramList.children()) {
        paramNameList.add(param.getString());
      }
      this.addCallParameters(paramNameList, n, call);
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
