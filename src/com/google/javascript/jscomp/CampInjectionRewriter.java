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
import com.google.javascript.jscomp.CampInjectionConsts.ClassMatchType;
import com.google.javascript.jscomp.CampInjectionConsts.JoinPointType;
import com.google.javascript.jscomp.CampInjectionConsts.MethodMatchType;
import com.google.javascript.jscomp.CampInjectionInfo.BindingInfo;
import com.google.javascript.jscomp.CampInjectionInfo.ClassInfo;
import com.google.javascript.jscomp.CampInjectionInfo.InterceptorInfo;
import com.google.javascript.jscomp.CampInjectionInfo.ModuleInfo;
import com.google.javascript.jscomp.CampInjectionInfo.ModuleInitializerInfo;
import com.google.javascript.jscomp.CampInjectionInfo.PrototypeInfo;
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
 *  Rewrite all DI and interceptors.
 *  Rewriter work like follows.
 *  
 *  <code>
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
 *      function(joinPoint) {
 *        console.log("log call before : " + joinPoint.getQualifiedName());
 *      }
 *    );
 *    
 *    //after
 *    //foo binding is inlined by compiler.
 *    this.bar = function() {
 *      return new BarClass(new FooClass);
 *    }
 *    this.jscomp$interceptor$0 = function(jscomp$joinPoint$this,
 *                                         jscomp$joinPoint$arguments,
 *                                         jscomp$joinPoint$className,
 *                                         jscomp$joinPoint$methodName,
 *                                         jscomp$joinPoint$result) {
 *      console.log("log call before : " + jscomp$joinPoint$className + ("." + jscomp$joinPoint$methodName));
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

  private static final DiagnosticType MESSAGE_CREATE_INSTANCE_TARGET_INVALID = DiagnosticType
      .error(
          "JSC_MSG_CREATE_INSTANCE_TARGET_NOT_VALID.",
          "The argument of camp.injections.injector.createInstance must be a constructor.");

  private static final DiagnosticType MESSAGE_BIND_CALL_FIRST_ARGUMENT_IS_INVALID = DiagnosticType
      .error(
          "JSC_MSG_BIND_CALL_FIRST_ARGUMENT_IS_NOT_VALID.",
          "The first argument of function bind must be a string which is key of injection.");

  private static final DiagnosticType MESSAGE_BIND_CALL_SECOND_ARGUMENT_IS_INVALID = DiagnosticType
      .error("JSC_MSG_BIND_CALL_SECOND_ARGUMENT_IS_NOT_VALID.",
          "The second argument of bind must be a inject value.");

  private static final DiagnosticType MESSAGE_BIND_INTERCEPTOR_FIRST_ARGUMENT_IS_INVALID = DiagnosticType
      .error("JSC_MSG_BIND_PROVIDER_FIRST_ARGUMENT_IS_NOT_VALID.",
          "The first argument of bindProvider must be a one of\n"
              + "  " + CampInjectionConsts.CLASS_MATCHER_IN_NAMESPACE + "\n"
              + "  " + CampInjectionConsts.CLASS_MATCHER_SUBCLASS_OF + "\n"
              + "  " + CampInjectionConsts.CLASS_MATCHER_INSTANCE_OF);

  private static final DiagnosticType MESSAGE_BIND_INTERCEPTOR_SECOND_ARGUMENT_IS_INVALID = DiagnosticType
      .error("JSC_MSG_DEFINE_PROVIDER__SECOND_ARGUMENT_IS_NOT_VALID.",
          "The second argument of bindProvider must be a one of\n"
              + " " + CampInjectionConsts.METHOD_MATCHER_LIKE);

  private static final DiagnosticType MESSAGE_BIND_INTERCEPTOR_THIRD_ARGUMENT_IS_INVALID = DiagnosticType
      .error("JSC_MSG_DEFINE_PROVIDER_THIRD_ARGUMENT_IS_INVALID.",
          "The thrid argument of bindProvider must be a one of\n"
              + " " + CampInjectionConsts.POINT_CUTS_AFTER + "\n"
              + " " + CampInjectionConsts.POINT_CUTS_BEFORE);

  private static final DiagnosticType MESSAGE_BIND_INTERCEPTOR_FOURTH_ARGUMENT_IS_INVALID = DiagnosticType
      .error(
          "JSC_MSG_BIND_INTERCEPTOR_FOURTH_ARGUMENT_IS_INVALID.",
          "The fourth argument of bindInterceptor must be a function expression which define behavior of the interceptor.");

  private static final DiagnosticType MESSAGE_BIND_PROVIDER_FIRST_ARGUMENT_IS_INVALID = DiagnosticType
      .error("JSC_MSG_DEFINE_PROVIDER_FIRST_ARGUMENT_IS_INVALID.",
          "The first argument of bindProvider must be a string or null.");

  private static final DiagnosticType MESSAGE_BIND_PROVIDER_SECOND_ARGUMENT_IS_INVALID = DiagnosticType
      .error("JSC_MSG_DEFINE_PROVIDER__SECOND_ARGUMENT_IS_INVALID.",
          "The second argument of bindProvider must be a class constructor.");

  private static final DiagnosticType MESSAGE_BIND_PROVIDER_THIRD_ARGUMENTS_IS_INVALID = DiagnosticType
      .error("JSC_MSG_DEFINE_PROVIDER__SECOND_ARGUMENT_IS_INVALID.",
          "The second argument of bindProvider must be a function expression.");

  private static final DiagnosticType MESSAGE_ACCESSED_TO_VIRTUAL_METHODS = DiagnosticType
      .error(
          "JSC_MSG_ACCESSED_TO_VIRTUAL_METHODS.",
          "The all methods of {0} can not use as the function object and can not access to method itself.\n"
              + "These are only virtual method because these methods are inlining by compiler.");

  private static final DiagnosticType MESSAGE_INVALID_ACCESS_TO_ENTITY = DiagnosticType
      .error(
          "JSC_MSG_INVALID_ACCESS_TO_ENTITY.",
          "The {0} can not use as a object and can not access to itself because that inlined by compiler.");

  private static final DiagnosticType MESSAGE_HAS_NO_SUCH_METHOD = DiagnosticType.error(
      "JSC_MSG_JOINPOINT_HAS_NO_SUCH_METHOD.",
      "The {0} has no such method #{1}().\nAvailable methods are\n"
          + "{2}\n");

  private static final DiagnosticType MESSAGE_MATCHER_IN_NAMESPACE_ARGUMENT_IS_INVALID = DiagnosticType
      .error(
          "JSC_MSG_MATCHER_IN_NAMESPACE_ARGUMENT_IS_INVALID.",
          "The first argument of camp.injections.Matcher.inNamespace must be a string expression of namespace.");

  private static final DiagnosticType MESSAGE_MATCHER_INSTANCE_OF_ARGUMENT_IS_INVALID = DiagnosticType
      .error(
          "JSC_MSG_MATCHER_INSTANCE_OF_ARGUMENT_IS_INVALID.",
          "The first argument of camp.injections.Matcher.instanceOf must be a constructor function.");

  private static final DiagnosticType MESSAGE_MATCHER_SUBCLASS_OF_ARGUMENT_IS_INVALID = DiagnosticType
      .error(
          "JSC_MSG_MATCHER_SUBCLASS_OF_ARGUMENT_IS_INVALID.",
          "The first argument of camp.injections.Matcher.subclassOf must be a constructor function.");

  private static final DiagnosticType MESSAGE_MATCHER_LIKE_ARGUMENT_IS_INVALID = DiagnosticType
      .error(
          "JSC_MSG_MATCHER_LIKE_ARGUMENT_IS_INVALID.",
          "The first argument of camp.injections.Matcher.like must be a string expression of the target method name.\n"
              + "The matcher is string expression which treat wilidcard(*) as a special character of the method name.");

  static final ImmutableSet<String> INJECTOR_METHOD_SET = new ImmutableSet.Builder<String>()
      .add(CampInjectionConsts.CREATE_INSTANCE)
      .build();

  static final ImmutableSet<String> BINDER_METHOD_SET = new ImmutableSet.Builder<String>()
      .add(CampInjectionConsts.BIND)
      .add(CampInjectionConsts.BIND_PROVIDER)
      .add(CampInjectionConsts.BIND_INTERCEPTOR)
      .build();

  static final ImmutableSet<String> JOIN_POINT_METHOD_LIST = new ImmutableSet.Builder<String>()
      .add(CampInjectionConsts.JOIN_POINT_GET_CLASS_NAME)
      .add(CampInjectionConsts.JOIN_POINT_GET_METHOD_NAME)
      .add(CampInjectionConsts.JOIN_POINT_GET_QUALIFIED_NAME)
      .add(CampInjectionConsts.JOIN_POINT_GET_ARGUMENTS)
      .add(CampInjectionConsts.JOIN_POINT_GET_RESULT)
      .add(CampInjectionConsts.JOIN_POINT_GET_THIS)
      .build();

  private CampInjectionInfo campInjectionInfo = CampInjectionInfo.getInstance();

  private AbstractCompiler compiler;

  private int interceptorId = 0;

  private int singletonId = 0;


  public CampInjectionRewriter(AbstractCompiler compiler) {
    this.compiler = compiler;
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
      Node binder = NodeUtil.getFunctionParameters(function).getFirstChild();
      
      if (binder != null) {
        //Traverse 'confgure' method's function body only.
        NodeTraversal.traverseRoots(
            this.compiler,
            Lists.newArrayList(function),
            new RewriteCallback(
                binder,
                new BindCallRewriter(moduleInfo.getModuleName(),
                    moduleInfo.isInterceptorRewrited())));
        
        //This rewriting process is cause the error or warning
        //if the compiler option "--jscomp_error checkTypes", "--jscomp_warnings checkTypes"
        //or "--warning_level VERBOSE" is specified
        //because rewritten method is called with no arguments.
        //So remove all parameters and jsdocs from this method.
        CampInjectionProcessor.getFunctionParameter(function)
            .detachChildren();
        this.removeJSDocInfoFromBindingMethod(function);
        
        moduleInfo.setInterceptorRewrited(true);
      }
    }
  }
  
  
  /**
   * Remove JSDocInfo from a assignment node of Module.prototype.configure. 
   * @param function a method definition node.
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
        NodeTraversal.traverseRoots(this.compiler, Lists.newArrayList(function),
            new RewriteCallback(
                injector, new ModuleInitializerRewriter(moduleConfigList)));
        
        //This rewriting process is cause the error or warning
        //"--jscomp_error checkTypes", "--jscomp_warnings checkTypes"
        //or "--warning_level VERBOSE" is specified because it change
        //the function into a anonymous function which calling with no arguments.  
        //So remove all parameters and jsdocs from this method.
        injector.detachFromParent();
        function.setJSDocInfo(null);
        
        this.rewriteCall(moduleConfigList, moduleInitCall, function);
      }
      
    }
  }


  private void rewriteCall(List<String> moduleConfigList, Node moduleInitCall, Node function) {
    function.detachFromParent();
    Node block = CampInjectionProcessor.getFunctionBody(function);
    CampInjectionProcessor.replaceNode(moduleInitCall, new Node(Token.CALL, function));

    List<String> copied = Lists.newArrayList();
    copied.addAll(moduleConfigList);
    Collections.reverse(copied);

    for (String config : copied) {
      Node var = CampInjectionProcessor.makeVar(
          CampInjectionProcessor.toLowerCase(CampInjectionProcessor.getValidVarName(config)),
          new Node(Token.NEW, CampInjectionProcessor.createQualifiedNameNode(config)));
      block.addChildBefore(var, block.getFirstChild());
      Node callConfigure = new Node(Token.EXPR_RESULT,
          new Node(Token.CALL,
              CampInjectionProcessor.createQualifiedNameNode(var.getFirstChild().getString() + "."
                  + CampInjectionConsts.MODULE_SETUP_METHOD_NAME)));
      block.addChildAfter(callConfigure, var);
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
          bindingInfo.registeredAsProvider(true);
          bindingInfo.setName(bindNameNode.getString());
        }
      } else {
        t.report(n, MESSAGE_BIND_PROVIDER_FIRST_ARGUMENT_IS_INVALID);
      }

      Node classNode = bindNameNode.getNext();

      if (classNode != null) {
        if (classNode.isName() || classNode.isGetProp()) {
          String name = classNode.getQualifiedName();
          if (!Strings.isNullOrEmpty(name)) {
            ClassInfo classInfo = campInjectionInfo.getClassInfo(name);
            if (classInfo != null) {
              Node provider = classNode.getNext();
              if (provider != null) {
                classInfo.setProviderNode(provider);
                if (bindingInfo != null) {
                  bindingInfo.setProvider(provider);
                  campInjectionInfo.putBindingInfo(this.className, bindingInfo);
                }
                bindingInfo = new BindingInfo();
                bindingInfo.registeredAsProvider(true);
                bindingInfo.setName(CampInjectionProcessor.toLowerCase(CampInjectionProcessor
                    .getValidVarName(classInfo.getClassName())));
                bindingInfo.setProvider(provider);
                campInjectionInfo.putBindingInfo(this.className, bindingInfo);
                this.rewriteProvider(provider, n, classInfo.getClassName(), bindName);
              } else {
                t.report(n, MESSAGE_BIND_PROVIDER_THIRD_ARGUMENTS_IS_INVALID);
              }
            }
          } else {
            t.report(n, MESSAGE_BIND_PROVIDER_SECOND_ARGUMENT_IS_INVALID);
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
          Node pointCuts = methodMatcher.getNext();
          if (pointCuts != null && pointCuts.isGetProp()) {
            Node interceptor = pointCuts.getNext();
            if (interceptor != null && interceptor.isFunction()) {
              InterceptorInfo interceptorInfo = this.getInterceptorInfo(t,
                  classMatcher.getFirstChild(),
                  methodMatcher.getFirstChild(), pointCuts, interceptor);
              this.rewriteInterceptor(t, n, interceptorInfo);
            } else {
              t.report(n, MESSAGE_BIND_INTERCEPTOR_FOURTH_ARGUMENT_IS_INVALID);
            }
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
        Node methodMatcher,
        Node pointCuts, Node interceptor) {
      InterceptorInfo interceptorInfo = new InterceptorInfo();
      String classMatchTypeCallName = classMatcher.getQualifiedName();
      this.setClassMatchType(t, classMatchTypeCallName, classMatcher, interceptorInfo);
      String methodMatchTypeCallName = methodMatcher.getQualifiedName();
      this.setMethodMatchType(t, methodMatchTypeCallName, methodMatcher, interceptorInfo);
      String pointCutsCall = pointCuts.getQualifiedName();
      this.setJoinPoint(t, pointCutsCall, pointCuts, interceptorInfo);
      interceptorInfo.setInterceptorNode(interceptor);
      campInjectionInfo.putInterceptorInfo(this.className, interceptorInfo);
      return interceptorInfo;
    }


    private void setClassMatchType(NodeTraversal t, String matchTypeCallName, Node classMatcher,
        InterceptorInfo interceptorInfo) {
      Node node = classMatcher.getNext();
      if (node != null) {
        if (matchTypeCallName.equals(CampInjectionConsts.CLASS_MATCHER_IN_NAMESPACE)) {
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
        } else if (matchTypeCallName.equals(CampInjectionConsts.CLASS_MATCHER_SUBCLASS_OF)) {
          if (node.isGetProp()) {
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
        } else if (matchTypeCallName.equals(CampInjectionConsts.CLASS_MATCHER_INSTANCE_OF)) {
          if (node.isGetProp()) {
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
        } else {
          t.report(classMatcher, MESSAGE_BIND_INTERCEPTOR_FIRST_ARGUMENT_IS_INVALID);
        }
      } else {
        t.report(classMatcher, MESSAGE_BIND_INTERCEPTOR_FIRST_ARGUMENT_IS_INVALID);
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
        } else {
          t.report(node, MESSAGE_BIND_INTERCEPTOR_SECOND_ARGUMENT_IS_INVALID);
        }
      } else {
        t.report(node, MESSAGE_BIND_INTERCEPTOR_SECOND_ARGUMENT_IS_INVALID);
      }
    }


    private void setJoinPoint(NodeTraversal t, String pointCuts, Node pointCutsNode,
        InterceptorInfo interceptorInfo) {
      if (pointCuts.equals(CampInjectionConsts.POINT_CUTS_AFTER)) {
        interceptorInfo.setJoinPoint(JoinPointType.AFTER);
      } else if (pointCuts.equals(CampInjectionConsts.POINT_CUTS_BEFORE)) {
        interceptorInfo.setJoinPoint(JoinPointType.BEFORE);
      } else {
        t.report(pointCutsNode, MESSAGE_BIND_INTERCEPTOR_THIRD_ARGUMENT_IS_INVALID);
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

      tmp.getParent().addChildAfter(
          new Node(Token.EXPR_RESULT, new Node(Token.ASSIGN,
              CampInjectionProcessor.createQualifiedNameNode(CampInjectionConsts.THIS + "."
                  + bindingName), expression.cloneTree())), tmp);
      tmp.detachFromParent();
    }


    private void rewriteProvider(Node function, Node n, String className, String bindingName) {
      n = CampInjectionProcessor.getStatementParent(n);
      if (bindingName != null) {
        n.getParent().addChildBefore(
            new Node(Token.EXPR_RESULT,
                new Node(Token.ASSIGN,
                    CampInjectionProcessor.createQualifiedNameNode(CampInjectionConsts.THIS + "." + bindingName),
                    function.cloneTree())), n);
      }

      Node provider = bindingName != null ? CampInjectionProcessor.createQualifiedNameNode(CampInjectionConsts.THIS
          + "." + bindingName)
          : function.cloneTree();
      n.getParent().addChildAfter(
          new Node(Token.EXPR_RESULT,
              new Node(Token.ASSIGN, CampInjectionProcessor.createQualifiedNameNode(CampInjectionConsts.THIS
                  + "."
                  + CampInjectionProcessor.toLowerCase(CampInjectionProcessor
                      .getValidVarName(className))),
                  provider)), n);
      n.detachFromParent();
    }


    private void rewriteInterceptor(NodeTraversal t, Node n, InterceptorInfo interceptorInfo) {
      Node function = interceptorInfo.getInterceptorNode();
      Node tmp = CampInjectionProcessor.getStatementParent(n);
      Preconditions.checkNotNull(tmp);
      String interceptorName = CampInjectionConsts.THIS + "." + CampInjectionConsts.INTERCEPTOR_NAME
          + interceptorId;

      Node paramList = function.getFirstChild().getNext();
      Node joinPoint = paramList.getFirstChild();
      if (joinPoint != null) {
        JoinPointRewriter joinPointRewriter = new JoinPointRewriter(joinPoint);
        RewriteCallback rewriteCallback = new RewriteCallback(joinPoint, joinPointRewriter);
        NodeTraversal.traverseRoots(compiler, Lists.newArrayList(function), rewriteCallback);
        interceptorInfo.setMethodNameAccess(joinPointRewriter.isMethodNameAccess());
        interceptorInfo.setClassNameAccess(joinPointRewriter.isClassNameAccess());

        paramList.detachChildren();
        for (Node param : joinPointRewriter.getParamList()) {
          paramList.addChildToBack(param);
        }
      }

      function.detachFromParent();
      tmp.getParent().addChildAfter(
          new Node(Token.EXPR_RESULT, new Node(Token.ASSIGN,
              CampInjectionProcessor.createQualifiedNameNode(interceptorName), function)), tmp);
      interceptorInfo.setName(CampInjectionConsts.INTERCEPTOR_NAME + interceptorId);
      interceptorId++;
      tmp.detachFromParent();
    }
  }


  private final class JoinPointRewriter extends AbstractNodeRewriter {

    private Node joinPoint;

    private String CONTEXT = "jscomp$joinPoint$context";

    private String ARGS = "jscomp$joinPoint$args";

    private String CLASS_NAME = "jscomp$joinPoint$className";

    private String METHOD_NAME = "jscomp$joinPoint$methodName";

    private String RESULT = "jscomp$joinPoint$result";

    private boolean classNameAccess = false;

    private boolean methodNameAccess = false;

    private final ImmutableList<Node> paramList = new ImmutableList.Builder<Node>()
        .add(Node.newString(Token.NAME, CONTEXT))
        .add(Node.newString(Token.NAME, ARGS))
        .add(Node.newString(Token.NAME, CLASS_NAME))
        .add(Node.newString(Token.NAME, METHOD_NAME))
        .add(Node.newString(Token.NAME, RESULT))
        .build();


    public ImmutableList<Node> getParamList() {
      return this.paramList;
    }


    public JoinPointRewriter(Node joinPoint) {
      super(CampInjectionConsts.JOIN_POINT, JOIN_POINT_METHOD_LIST);
      this.joinPoint = joinPoint;
      JSDocInfoBuilder jsdocInfoBuilder = new JSDocInfoBuilder(false);
      Node anyType = Node.newString(Token.NAME, "*");
      Node stringType = Node.newString(Token.NAME, "string");
      Node argumentsType = Node.newString(Token.NAME, "Arguments");

      String sourceFileName = joinPoint.getSourceFileName();
      jsdocInfoBuilder.recordParameter(CONTEXT,
          new JSTypeExpression(anyType, sourceFileName));
      jsdocInfoBuilder.recordParameter(ARGS,
          new JSTypeExpression(argumentsType, sourceFileName));
      jsdocInfoBuilder.recordParameter(CLASS_NAME,
          new JSTypeExpression(stringType, sourceFileName));
      jsdocInfoBuilder.recordParameter(METHOD_NAME, new JSTypeExpression(stringType.cloneNode(),
          sourceFileName));
      jsdocInfoBuilder.recordParameter(RESULT, JSTypeExpression
          .makeOptionalArg(new JSTypeExpression(anyType.cloneNode(), sourceFileName)));

      jsdocInfoBuilder.build(this.joinPoint.getParent());
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
        if (method.equals(CampInjectionConsts.JOIN_POINT_GET_ARGUMENTS)) {
          CampInjectionProcessor.replaceNode(callNode, Node.newString(Token.NAME, ARGS));
        } else if (method.equals(CampInjectionConsts.JOIN_POINT_GET_CLASS_NAME)) {
          this.classNameAccess = true;
          CampInjectionProcessor.replaceNode(callNode, Node.newString(Token.NAME, CLASS_NAME));
        } else if (method.equals(CampInjectionConsts.JOIN_POINT_GET_METHOD_NAME)) {
          this.methodNameAccess = true;
          CampInjectionProcessor.replaceNode(callNode, Node.newString(Token.NAME, METHOD_NAME));
        } else if (method.equals(CampInjectionConsts.JOIN_POINT_GET_QUALIFIED_NAME)) {
          this.methodNameAccess = true;
          this.classNameAccess = true;
          CampInjectionProcessor.replaceNode(callNode,
              new Node(Token.ADD, Node.newString(Token.NAME, CLASS_NAME),
                  new Node(
                      Token.ADD,
                      Node.newString("."), Node.newString(Token.NAME, METHOD_NAME))));
        } else if (method.equals(CampInjectionConsts.JOIN_POINT_GET_THIS)) {
          CampInjectionProcessor.replaceNode(callNode, Node.newString(Token.NAME, CONTEXT));
        } else if (method.equals(CampInjectionConsts.JOIN_POINT_GET_RESULT)) {
          CampInjectionProcessor.replaceNode(callNode, Node.newString(Token.NAME, RESULT));
        }
      }
    }
  }


  private final class ModuleInitializerRewriter extends AbstractNodeRewriter {
    private Map<String, Map<String, BindingInfo>> allBindingInfoMap = Maps.newHashMap();

    private Map<String, List<InterceptorInfo>> interceptorMap = Maps.newHashMap();

    private Map<ClassInfo, String> singletonMap = Maps.newHashMap();


    public ModuleInitializerRewriter(List<String> moduleConfigList) {
      super(CampInjectionConsts.INJECTOR, INJECTOR_METHOD_SET);
      singletonId++;
      for (String moduleName : moduleConfigList) {
        ModuleInfo moduleInfo = campInjectionInfo.getModuleInfo(moduleName);
        if (moduleInfo != null) {
          this.allBindingInfoMap.put(moduleInfo.getModuleName(), moduleInfo.getBindingInfoMap());
          List<InterceptorInfo> infoList = campInjectionInfo.getInterceptorInfo(moduleName);
          if (infoList != null) {
            List<InterceptorInfo> list = Lists.newArrayList();
            this.interceptorMap.put(CampInjectionProcessor.toLowerCase(CampInjectionProcessor
                .getValidVarName(moduleName)), list);
            for (InterceptorInfo interceptorInfo : infoList) {
              list.add(interceptorInfo);
            }
          }
        }
      }
      this.weavingInterceptor();
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
      ClassInfo info = campInjectionInfo.getClassInfo(className);
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
              child = this.makeNewCallFromProvider(info.getProviderNode(), new Node(Token.CALL,
                  CampInjectionProcessor.createQualifiedNameNode(targetClassVar + "." + varName)));
              break;
            }
          }
        } else {
          child = this.makeNewCall(info);
        }

        if (child != null) {
          child.copyInformationFromForTree(createInstanceCall);
          if (child != null) {
            createInstanceCall.getParent().replaceChild(createInstanceCall, child);
          }
        }
      }
    }


    private Node resolveBinding(String bindingName) {
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
              info = campInjectionInfo.getClassInfo(name);
            } else if (entity.isName() && (name = entity.getString()) != null) {
              info = campInjectionInfo.getClassInfo(name);
            }
          }

          if (info != null) {
            if (info.getProviderNode() != null) {
              String varName = CampInjectionProcessor.toLowerCase(CampInjectionProcessor
                  .getValidVarName(info.getClassName()));
              ret = this.makeNewCallFromProvider(info.getProviderNode(), new Node(Token.CALL,
                  CampInjectionProcessor.createQualifiedNameNode(lowerClassName + "." + varName)));
            } else {
              ret = this.makeNewCall(info);
            }
          } else {
            Node provider = bindingInfo.getProviderNode();
            if (provider != null) {
              ret = this.makeNewCallFromProvider(provider, new Node(Token.CALL,
                  CampInjectionProcessor.createQualifiedNameNode(lowerClassName + "."
                      + bindingInfo.getName())));
            } else {
              ret = CampInjectionProcessor.createQualifiedNameNode(lowerClassName + "."
                  + bindingName);
            }
          }
          return ret;
        } else {
          List<InterceptorInfo> interceptorInfoList = campInjectionInfo
              .getInterceptorInfo(className);
          if (interceptorInfoList != null) {
            for (InterceptorInfo interceptorInfo : interceptorInfoList) {
              String name = interceptorInfo.getName();
              if (!Strings.isNullOrEmpty(name) && name.equals(bindingName)) {
                return CampInjectionProcessor.createQualifiedNameNode(lowerClassName + "."
                    + bindingName);
              }
            }
          }
        }
      }
      return new Node(Token.NULL);
    }


    private void weavingInterceptor() {
      for (String moduleName : this.allBindingInfoMap.keySet()) {
        List<InterceptorInfo> infoList = campInjectionInfo.getInterceptorInfo(moduleName);
        if (infoList != null) {
          for (InterceptorInfo interceptorInfo : infoList) {
            Map<String, ClassInfo> classInfoMap = campInjectionInfo.getClassInfoMap();
            for (ClassInfo classInfo : classInfoMap.values()) {
              this.weavingInterceptorIfMatched(interceptorInfo, classInfo);
            }
          }
        }
      }
    }


    private void weavingInterceptorIfMatched(InterceptorInfo interceptorInfo, ClassInfo classInfo) {
      if (this.matchClass(interceptorInfo, classInfo)) {
        this.rewriteConstructor(classInfo, interceptorInfo);
        this.rewriteMethodIfMatched(interceptorInfo, classInfo);
      }
    }


    private boolean matchClass(InterceptorInfo interceptorInfo, ClassInfo classInfo) {
      ClassMatchType classMatchType = interceptorInfo.getClassMatchType();
      if (classMatchType != null) {
        String className = classInfo.getClassName();
        switch (classMatchType) {
        case IN_NAMESPACE:
          String reg = "^" + interceptorInfo.getClassMatcher().replaceAll("\\.", "\\.") + "\\..*";
          if (className.matches(reg)) {
            return true;
          }
          break;
        case SUBCLASS_OF:
          JSDocInfo jsDocInfo = classInfo.getJSDocInfo();
          if (jsDocInfo != null) {
            JSTypeExpression exp = jsDocInfo.getBaseType();
            if (exp != null) {
              if (this.checkType(exp.getRoot(), interceptorInfo.getClassMatcher())) {
                return true;
              }
            }
          }
          break;
        case INSTANCE_OF:
          return interceptorInfo.getClassMatcher().equals(className);
        }
      }
      return false;
    }


    private void rewriteMethodIfMatched(InterceptorInfo interceptorInfo, ClassInfo classInfo) {
      String methodMatcher = interceptorInfo.getMethodMatcher();
      String methodMatcherReg = "^" + methodMatcher.replaceAll("\\*", ".*");
      Map<String, PrototypeInfo> prototypeInfoMap = classInfo.getPrototypeInfoMap();
      if (prototypeInfoMap != null) {
        for (String methodName : prototypeInfoMap.keySet()) {
          if (methodName.matches(methodMatcherReg)) {
            boolean insertAfter = interceptorInfo.getJoinPoint() == JoinPointType.AFTER;
            PrototypeInfo prototypeInfo = prototypeInfoMap.get(methodName);
            if (!prototypeInfo.hasInterceptor(interceptorInfo.getName())) {
              prototypeInfo.putInterceptor(interceptorInfo.getName());
              Node function = prototypeInfo.getFunction();
              Node block = function.getLastChild();
              Node interceptor = this.makeInterceptorCall(interceptorInfo,
                  classInfo.getClassName(), methodName, insertAfter);
              if (!prototypeInfo.isWeaved() && insertAfter) {
                this.rewriteReturn(prototypeInfo, block);
                prototypeInfo.setWeaved(true);
              }
              Node lastInserted = prototypeInfo.getLastInsertedInterceptor();
              if (insertAfter) {
                block.addChildBefore(interceptor, block.getLastChild());
              } else {
                if (lastInserted != null) {
                  block.addChildAfter(interceptor, lastInserted);
                } else {
                  block.addChildToFront(interceptor);
                }
              }
              prototypeInfo.setLastInsertedInterceptor(interceptor);
            }
          }
        }
      }
    }


    private void rewriteReturn(PrototypeInfo prototypeInfo, Node block) {
      Node var = new Node(Token.VAR, Node.newString(Token.NAME, CampInjectionConsts.INTERCEPTOR_RESULT));
      block.addChildToFront(var);
      NodeTraversal.traverseRoots(compiler, Lists.newArrayList(prototypeInfo.getFunction()),
          new ReturnStatementRewriter(var));
      block.addChildToBack(new Node(Token.RETURN, var.getFirstChild().cloneNode()));
    }


    private final class ReturnStatementRewriter extends AbstractPostOrderCallback {
      private Node var;


      public ReturnStatementRewriter(Node var) {
        this.var = var;
      }


      @Override
      public void visit(NodeTraversal t, Node n, Node parent) {
        if (n.isReturn()) {
          Node newChild = var.cloneTree();
          if (n.getFirstChild() != null) {
            newChild.getFirstChild().addChildToBack(n.getFirstChild().cloneTree());
          } else {
            newChild.getFirstChild().addChildToBack(CampInjectionProcessor.makeUndefined());
          }
          n.getParent().replaceChild(n, newChild);
        }
      }
    }


    private Node makeInterceptorCall(InterceptorInfo interceptorInfo, String className,
        String methodName, boolean isAfter) {
      Node nameNode = CampInjectionProcessor.createQualifiedNameNode(CampInjectionConsts.THIS + "."
          + interceptorInfo.getName());
      Node call = new Node(Token.CALL, nameNode.cloneTree(), new Node(Token.THIS),
          Node.newString(Token.NAME, "arguments"));
      if (interceptorInfo.isClassNameAccess()) {
        call.addChildToBack(Node.newString(className));
      } else {
        call.addChildToBack(Node.newString(""));
      }
      if (interceptorInfo.isMethodNameAccess()) {
        call.addChildToBack(Node.newString(methodName));
      } else {
        call.addChildToBack(Node.newString(""));
      }
      if (isAfter) {
        call.addChildToBack(Node.newString(Token.NAME, CampInjectionConsts.INTERCEPTOR_RESULT));
      }

      return new Node(Token.EXPR_RESULT, new Node(Token.AND, nameNode, call));
    }


    private boolean checkType(Node typeNode, String typeName) {
      if (typeNode.isString()) {
        String name = typeNode.getString();
        if (name.equals(typeName)) {
          return true;
        }
      }

      for (Node child = typeNode.getFirstChild(); child != null; child = child.getNext()) {
        if (this.checkType(typeNode, typeName)) {
          return true;
        }
      }
      return false;
    }


    private Node makeNewCallFromProvider(Node function, Node call) {
      Node paramList = function.getFirstChild().getNext();
      for (Node param : paramList.children()) {
        call.addChildToBack(this.resolveBinding(param.getString()));
      }
      return call;
    }


    private Node makeNewCall(ClassInfo classInfo) {
      Node newCall;
      Map<String, InterceptorInfo> matchedMap = Maps.newHashMap();

      for (String moduleName : this.interceptorMap.keySet()) {
        List<InterceptorInfo> interceptorInfoList = this.interceptorMap.get(moduleName);
        for (InterceptorInfo interceptorInfo : interceptorInfoList) {
          if (this.matchClass(interceptorInfo, classInfo)) {
            matchedMap.put(moduleName, interceptorInfo);
          }
        }
      }

      if (classInfo.isSingleton()) {
        newCall = this.makeSingleton(classInfo, matchedMap);
      } else {
        newCall = this.makeSimpleNewCall(classInfo);
        if (classInfo.getSetterList() != null) {
          return this.makeNewCallScope(newCall, classInfo);
        }
      }
      return newCall;
    }


    private void rewriteConstructor(ClassInfo classInfo, InterceptorInfo interceptorInfo) {
      List<String> paramList = classInfo.getParamList();
      Node block = classInfo.getConstructorNode().getLastChild();
      Node paramListNode = classInfo.getConstructorNode().getFirstChild().getNext();
      String name = interceptorInfo.getName();

      if (paramList.indexOf(name) == -1) {
        Node nameNode = Node.newString(Token.NAME, name);
        paramList.add(name);
        paramListNode.addChildToBack(nameNode);
        block.addChildToFront(new Node(Token.EXPR_RESULT, new Node(Token.ASSIGN,
            CampInjectionProcessor.createQualifiedNameNode(CampInjectionConsts.THIS + "."
                + interceptorInfo.getName()), nameNode.cloneNode())));
      }
    }


    private Node makeSimpleNewCall(ClassInfo classInfo) {
      Node newCall = new Node(Token.NEW, CampInjectionProcessor.createQualifiedNameNode(classInfo
          .getClassName()));
      for (String param : classInfo.getParamList()) {
        newCall.addChildToBack(this.resolveBinding(param));
      }
      return newCall;
    }


    private Node makeSingleton(ClassInfo classInfo, Map<String, InterceptorInfo> matchedMap) {
      if (!this.singletonMap.containsKey(classInfo)) {
        Node singletonCall = classInfo.getSingletonCallNode();
        String name = CampInjectionConsts.GET_INJECTIED_INSTANCE + singletonId;
        Node getInstanceMirror = Node.newString(name);
        Node className = singletonCall.getFirstChild().getNext().cloneTree();
        Node instanceHolder = new Node(Token.GETPROP, className.cloneTree(),
            Node.newString(CampInjectionConsts.INJECTED_INSTANCE));
        Node newCall = this.makeSimpleNewCall(classInfo);

        Node instaniationBlock = new Node(Token.BLOCK, new Node(Token.EXPR_RESULT, new Node(
            Token.ASSIGN, instanceHolder.cloneTree(), newCall)));

        if (classInfo.getSetterList() != null) {
          this.makeNewCallScopeBody(instaniationBlock, instanceHolder, classInfo);
        }

        Node paramList = new Node(Token.PARAM_LIST);

        for (String moduleName : this.allBindingInfoMap.keySet()) {
          paramList
              .addChildToBack(Node.newString(Token.NAME,
                  CampInjectionProcessor.toLowerCase(CampInjectionProcessor
                      .getValidVarName(moduleName))));
        }

        Node expr = new Node(Token.EXPR_RESULT, new Node(Token.ASSIGN, new Node(Token.GETPROP,
            className, getInstanceMirror), new Node(Token.FUNCTION,
            Node.newString(Token.NAME, ""), paramList, new Node(Token.BLOCK,
                new Node(Token.IF, new Node(Token.NOT, instanceHolder.cloneTree()),
                    instaniationBlock), new Node(Token.RETURN, instanceHolder.cloneTree())))));

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
        }
      }

      Node call = new Node(Token.CALL, CampInjectionProcessor.createQualifiedNameNode(classInfo
          .getClassName() + "."
          + this.singletonMap.get(classInfo)));
      for (String moduleName : this.allBindingInfoMap.keySet()) {
        call.addChildToBack(Node.newString(Token.NAME,
            CampInjectionProcessor.toLowerCase(CampInjectionProcessor.getValidVarName(moduleName))));
      }
      return call;
    }


    private Node makeNewCallScope(Node newCall, ClassInfo classInfo) {
      Node instanceVar = Node.newString(Token.NAME, "instance");
      instanceVar.addChildToBack(newCall);

      Node block = new Node(Token.BLOCK, new Node(Token.VAR, instanceVar));

      this.makeNewCallScopeBody(block, instanceVar, classInfo);

      block.addChildToBack(new Node(Token.RETURN, instanceVar.cloneNode()));

      return new Node(Token.CALL, new Node(Token.FUNCTION, Node.newString(Token.NAME, ""),
          new Node(Token.PARAM_LIST), block));
    }


    private void makeNewCallScopeBody(Node block, Node instanceVar, ClassInfo classInfo) {
      instanceVar = instanceVar.cloneTree();
      for (String setterName : classInfo.getSetterList()) {
        PrototypeInfo prototypeInfo = classInfo.getPrototypeInfo(setterName);
        if (prototypeInfo != null) {
          Node setterCall = new Node(Token.CALL,
              CampInjectionProcessor.createQualifiedNameNode(instanceVar.getQualifiedName() + "."
                  + setterName));
          for (String param : prototypeInfo.getParamList()) {
            setterCall.addChildToBack(this.resolveBinding(param));
          }
          block.addChildToBack(new Node(Token.EXPR_RESULT, setterCall));
        }
      }
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


    public boolean isFirstArgument(NodeTraversal t, Node n, Node firstChild) {
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
        if (this.isFirstArgument(t, n, firstArgument)) {
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
        if (firstChild.isName() && this.isFirstArgument(t, firstChild, firstArgument)) {
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
          if (processor.isFirstArgument(t, n.getFirstChild(), this.firstArgument)) {
            processor.rewrite(t, n, this.firstArgument);
          }
        }
      }
    }
  }
}
