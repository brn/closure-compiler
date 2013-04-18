package com.google.javascript.jscomp;

import java.util.regex.Pattern;

public class DIConsts {

  enum ClassMatchType {
    IN_NAMESPACE,
    SUB_NAMESPACE,
    SUBCLASS_OF,
    INSTANCE_OF,
    ANY
  }


  enum MethodMatchType {
    LIKE,
    ANY
  }

  public static final String INJECTOR = "camp.injections.Injector";
  static final String BINDER = "camp.injections.Binder";
  static final String METHOD_INVOCATION = "camp.injections.MethodInvocation";
  static final String METHOD_INVOCATION_GET_CONSTRUCTOR_NAME = "getConstructorName";
  static final String METHOD_INVOCATION_GET_METHOD_NAME = "getMethodName";
  static final String METHOD_INVOCATION_GET_QUALIFIED_NAME = "getQualifiedName";
  static final String METHOD_INVOCATION_GET_ARGUMENTS = "getArguments";
  static final String METHOD_INVOCATION_PROCEED = "proceed";
  static final String METHOD_INVOCATION_GET_THIS = "getThis";
  static final String CLASS_MATCHERS_IN_NAMESPACE = "camp.injections.Matchers.inNamespace";
  static final String CLASS_MATCHERS_IN_SUBNAMESPACE = "camp.injections.Matchers.inSubnamespace";
  static final String CLASS_MATCHERS_SUBCLASS_OF = "camp.injections.Matchers.subclassOf";
  static final String CLASS_MATCHERS_INSTANCE_OF = "camp.injections.Matchers.instanceOf";
  static final String MATCHERS_ANY = "camp.injections.Matchers.any";
  static final String METHOD_MATCHER_LIKE = "camp.injections.Matchers.like";
  static final String INTERCEPTOR_RESULT = "jscomp$interceptor$result";
  static final String INTERCEPTOR_NAME = "jscomp$interceptor$";
  static final String GET_INJECTIED_INSTANCE = "jscomp$getInjectedInstance$";
  static final String INJECTED_INSTANCE = "_jscomp$injectedInstance";
  static final String BIND = "bind";
  static final String BIND_PROVIDER = "bindProvider";
  static final String BIND_INTERCEPTOR = "bindInterceptor";
  static final String GET_INSTANCE = "getInstance";
  static final String GET_INSTANCE_BY_NAME = "getInstanceByName";
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
  static final String INTERCEPTOR_DEF_SCOPE = "jscomp$intercepto$defScope";
  static final String ENHANCED_CONSTRUCTOR_FORMAT = "JSComp$enhanced$%s";
  static final String GOOG_BASE = "goog.base";
  static final String GOOG_INHERITS = "goog.inherits";
  static final Pattern INJECTON_PARSE_REG = Pattern.compile("([a-zA-Z_$][\\w_$]*)(?:\\(([\\s\\S]+)\\))");
}
