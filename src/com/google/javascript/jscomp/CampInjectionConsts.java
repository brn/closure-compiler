package com.google.javascript.jscomp;

public class CampInjectionConsts {

  enum ClassMatchType {
    IN_NAMESPACE,
    SUBCLASS_OF,
    INSTANCE_OF
  }


  enum MethodMatchType {
    LIKE
  }


  enum JoinPointType {
    AFTER,
    BEFORE
  }

  public static final String INJECTOR = "camp.injections.Injector";
  static final String BINDER = "camp.injections.Binder";
  static final String METHOD_INVOCATION = "camp.injections.MethodInvocation";
  static final String METHOD_INVOCATION_GET_CLASS_NAME = "getClassName";
  static final String METHOD_INVOCATION_GET_METHOD_NAME = "getMethodName";
  static final String METHOD_INVOCATION_GET_QUALIFIED_NAME = "getQualifiedName";
  static final String METHOD_INVOCATION_GET_ARGUMENTS = "getArguments";
  static final String METHOD_INVOCATION_PROCEED = "proceed";
  static final String METHOD_INVOCATION_GET_THIS = "getThis";
  static final String CLASS_MATCHER_IN_NAMESPACE = "camp.injections.Matcher.inNamespace";
  static final String CLASS_MATCHER_SUBCLASS_OF = "camp.injections.Matcher.subclassOf";
  static final String CLASS_MATCHER_INSTANCE_OF = "camp.injections.Matcher.instanceOf";
  static final String METHOD_MATCHER_LIKE = "camp.injections.Matcher.like";
  static final String POINT_CUTS_AFTER = "camp.injections.Matcher.PointCuts.AFTER";
  static final String POINT_CUTS_BEFORE = "camp.injections.Matcher.PointCuts.BEFORE";
  static final String INTERCEPTOR_RESULT = "jscomp$interceptor$result";
  static final String INTERCEPTOR_NAME = "jscomp$interceptor$";
  static final String GET_INJECTIED_INSTANCE = "jscomp$getInjectedInstance$";
  static final String INJECTED_INSTANCE = "_jscomp$injectedInstance";
  static final String BIND = "bind";
  static final String BIND_PROVIDER = "bindProvider";
  static final String BIND_INTERCEPTOR = "bindInterceptor";
  static final String CREATE_INSTANCE = "createInstance";
  static final String THIS = "this";
  static final String PROTOTYPE = "prototype";
  static final String MODULE_INIT_CALL = "camp.injections.modules.init";
  static final String MODULE_INTERFACE = "camp.injections.Module";
  static final String INJECT_CALL = "camp.injections.Injector.inject";
  static final String SINGLETON_CALL = "goog.addSingletonGetter";
  static final String PROTOTYPE_REGEX = "(.*\\.prototype\\..*|.*\\.prototype$)";
  static final String MODULE_SETUP_METHOD_NAME = "configure";
  static final String INTERCEPTOR_ARGUMENTS = "jscomp$interceptor$args";
  static final String SLICE = "Array.prototype.slice.call";
  static final String THIS_REFERENCE = "jscomp$interceptor$this";

}
