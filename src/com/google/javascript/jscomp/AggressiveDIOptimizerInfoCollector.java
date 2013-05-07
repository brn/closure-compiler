package com.google.javascript.jscomp;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.javascript.jscomp.AggressiveDIOptimizerInfo.MethodInjectionInfo;
import com.google.javascript.jscomp.DIConsts.ClassMatchType;
import com.google.javascript.jscomp.DIConsts.MethodMatchType;
import com.google.javascript.jscomp.AggressiveDIOptimizerInfo.BindingInfo;
import com.google.javascript.jscomp.AggressiveDIOptimizerInfo.BindingType;
import com.google.javascript.jscomp.AggressiveDIOptimizerInfo.ConstructorInfo;
import com.google.javascript.jscomp.AggressiveDIOptimizerInfo.InjectorInfo;
import com.google.javascript.jscomp.AggressiveDIOptimizerInfo.InterceptorInfo;
import com.google.javascript.jscomp.AggressiveDIOptimizerInfo.ModuleInfo;
import com.google.javascript.jscomp.AggressiveDIOptimizerInfo.ModuleInitializerInfo;
import com.google.javascript.jscomp.AggressiveDIOptimizerInfo.PrototypeInfo;
import com.google.javascript.jscomp.AggressiveDIOptimizerInfo.ScopeType;
import com.google.javascript.jscomp.NodeTraversal.AbstractPostOrderCallback;
import com.google.javascript.jscomp.Scope.Var;
import com.google.javascript.rhino.JSDocInfo;
import com.google.javascript.rhino.JSTypeExpression;
import com.google.javascript.rhino.Node;
import com.google.javascript.rhino.Token;

final class AggressiveDIOptimizerInfoCollector {

  static final DiagnosticType MESSAGE_INJECTION_IS_ALREADY_SPECIFIED = DiagnosticType
      .error("JSC_MSG_INJECTION_IS_AMBIGUOUS",
          "The method injection target {0} of a constructor {1} is already specified.");

  static final DiagnosticType MESSAGE_INVALID_METHOD_INJECTION_SPECIFICATION = DiagnosticType
      .error(
          "JSC_MSG_INVALID_METHOD_SPECIFICATION",
          "The string expression '{0}' is invalid method injection specification.");

  static final DiagnosticType MESSAGE_INJECTION_TARGET_NOT_EXISTS_OR_PARSABLE = DiagnosticType
      .error(
          "JSC_MSG_METHOD_NOT_FOUND",
          "The injection target {0} of the constructor {1} is not exists in prototype chain or is not parsable. "
              +
              "Compiler is parse only the prototypes which are the function directly assigned. " +
              "If you declare prototype which is non-trivial style, " +
              "you should specify not only a method name but also parameters " +
              "in 'camp.injections.Injector.inject' call, like 'setFoo(foo,bar,baz)'.");

  static final DiagnosticType MESSAGE_PROTOTYPE_FUNCTION_IS_AMBIGUOUS = DiagnosticType.error(
      "JSC_MSG_PROTOTYPE_FUNCTION_IS_AMBIGUOUS", "The prototype {0} is ambiguous.");

  static final DiagnosticType MESSAGE_FUNCTION_IS_CALLED_INSIDE_OF_CONDITIONAL = DiagnosticType
      .warning(
          "JSC_MSG_BINDING_IS_CALLED_INSIDE_OF_CONDITIONAL",
          "Call of the function {0} inside of the conditional statement/expression may cause undefined behaviour.");

  static final DiagnosticType MESSAGE_MODULE_DEFINITION_IS_DUPLICATED = DiagnosticType
      .error(
          "JSC_MSG_CONSTRUCTOR_FUNCTION_IS_ANBIGUOUS.",
          "The module {0} has duplicated definition.");

  static final DiagnosticType MESSAGE_BINDING_DEFINITION_IS_AMBIGUOUS = DiagnosticType
      .error(
          "JSC_MSG_BINDING_DEFINITION_IS_ANBIGUOUS.",
          "The binding {0} in module {1} is ambiguous.");

  static final DiagnosticType MESSAGE_CONSTRUCTOR_BINDING_DEFINITION_IS_AMBIGUOUS = DiagnosticType
      .error(
          "JSC_MSG_CONSTRUCTOR_BINDING_DEFINITION_IS_ANBIGUOUS.",
          "The constructor binding {0} in module {1} is ambiguous. The constructor binding must be a unique.");

  static final DiagnosticType MESSAGE_FIRST_ARGUMENT_OF_MODULE_INITIALIZER_IS_INVALID = DiagnosticType
      .error(
          "JSC_MSG_FIRST_ARGUMENT_OF_MODULE_INITIALIZER_IS_INVALID.",
          "The first argument of camp.injections.modules.init must be a Array of camp.dependecies.Module implementation.");

  static final DiagnosticType MESSAGE_FIRST_ARGUMENT_OF_MODULE_INITIALIZER_IS_EMPTY = DiagnosticType
      .warning(
          "JSC_MSG_FIRST_ARGUMENT_OF_MODULE_INITIALIZER_IS_EMPTY.",
          "The first argument of camp.injections.modules.init is empty.");

  static final DiagnosticType MESSAGE_SECOND_ARGUMENT_OF_MODULE_INITIALIZER_IS_INVALID = DiagnosticType
      .error(
          "JSC_MSG_FIRST_ARGUMENT_OF_MODULE_INITIALIZER_IS_INVALID.",
          "The second argument of camp.injections.modules.init must be a function expression.");

  static final DiagnosticType MESSAGE_FIRST_ARGUMENT_OF_INJECT_IS_INVALID = DiagnosticType
      .error(
          "JSC_MSG_FIRST_ARGUMENT_OF_INJECT_IS_INVALID.",
          "The first argument of camp.injections.Injector.inject must be a constructor function.");

  static final DiagnosticType MESSAGE_SECOND_ARGUMENT_OF_INJECT_IS_INVALID = DiagnosticType
      .error(
          "JSC_MSG_SECOND_ARGUMENT_OF_INJECT_IS_INVALID.",
          "The second argument and rest of camp.injections.Injector.inject must be a string expression which is method name of injection target.");

  static final DiagnosticType MESSAGE_GET_INSTANCE_TARGET_INVALID = DiagnosticType
      .error(
          "JSC_MSG_CREATE_INSTANCE_TARGET_NOT_VALID.",
          String.format("The argument of %s must be a constructor.",
              DIConsts.GET_INSTANCE));

  static final DiagnosticType MESSAGE_GET_INSTANCE_BY_NAME_TARGET_INVALID = DiagnosticType
      .error(
          "JSC_MSG_CREATE_INSTANCE_TARGET_BY_NAME_NOT_VALID.",
          String.format("The argument of %s must be a string.",
              DIConsts.GET_INSTANCE_BY_NAME));

  static final DiagnosticType MESSAGE_BIND_CALL_FIRST_ARGUMENT_IS_INVALID = DiagnosticType
      .error(
          "JSC_MSG_BIND_CALL_FIRST_ARGUMENT_IS_NOT_VALID.",
          "The first argument of function bind must be a string which is key of injection.");

  private static final ImmutableMap<BindingType, String> BINDING_ERROR_MAP = new ImmutableMap.Builder<BindingType, String>()
      .put(
          BindingType.TO,
          String.format("The argument of method %s must be a constructor function.",
              AggressiveDIOptimizerInfo.bindingTypeMap.get(BindingType.TO)))
      .put(
          BindingType.TO_INSTANCE,
          String.format("The argument of method %s must be a expression.",
              AggressiveDIOptimizerInfo.bindingTypeMap.get(BindingType.TO_INSTANCE)))
      .put(
          BindingType.TO_PROVIDER,
          String.format("The argument of method %s must be a function expression.",
              AggressiveDIOptimizerInfo.bindingTypeMap.get(BindingType.TO_PROVIDER)))
      .build();

  static final DiagnosticType MESSAGE_BIND_CALL_SECOND_ARGUMENT_IS_INVALID = DiagnosticType
      .error("JSC_MSG_BIND_CALL_SECOND_ARGUMENT_IS_NOT_VALID.", "{0}");

  static final DiagnosticType MESSAGE_BIND_INTERCEPTOR_FIRST_ARGUMENT_IS_INVALID = DiagnosticType
      .error("JSC_MSG_BIND_PROVIDER_FIRST_ARGUMENT_IS_NOT_VALID.",
          "The first argument of bindProvider must be a one of\n"
              + "  " + DIConsts.CLASS_MATCHERS_IN_NAMESPACE + "\n"
              + "  " + DIConsts.CLASS_MATCHERS_IN_SUBNAMESPACE + "\n"
              + "  " + DIConsts.CLASS_MATCHERS_SUBCLASS_OF + "\n"
              + "  " + DIConsts.CLASS_MATCHERS_INSTANCE_OF + "\n"
              + "  " + DIConsts.MATCHERS_ANY);

  static final DiagnosticType MESSAGE_BIND_INTERCEPTOR_SECOND_ARGUMENT_IS_INVALID = DiagnosticType
      .error("JSC_MSG_DEFINE_PROVIDER__SECOND_ARGUMENT_IS_NOT_VALID.",
          "The second argument of bindProvider must be a one of\n"
              + "  " + DIConsts.METHOD_MATCHER_LIKE + "\n"
              + "  " + DIConsts.MATCHERS_ANY);

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
              AggressiveDIOptimizerInfo.getScopeTypeString());

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

  private static final ImmutableSet<String> BINDER_METHOD_SET = new ImmutableSet.Builder<String>()
      .add(DIConsts.BIND)
      .add(DIConsts.BIND_PROVIDER)
      .add(DIConsts.BIND_INTERCEPTOR)
      .build();

  private static final ImmutableSet<String> INJECTOR_METHOD_SET = new ImmutableSet.Builder<String>()
      .add(DIConsts.GET_INSTANCE)
      .add(DIConsts.GET_INSTANCE_BY_NAME)
      .build();

  private static final ImmutableSet<String> METHOD_INVOCATION_METHOD_LIST = new ImmutableSet.Builder<String>()
      .add(DIConsts.METHOD_INVOCATION_GET_CONSTRUCTOR_NAME)
      .add(DIConsts.METHOD_INVOCATION_GET_METHOD_NAME)
      .add(DIConsts.METHOD_INVOCATION_GET_QUALIFIED_NAME)
      .add(DIConsts.METHOD_INVOCATION_GET_ARGUMENTS)
      .add(DIConsts.METHOD_INVOCATION_PROCEED)
      .add(DIConsts.METHOD_INVOCATION_GET_THIS)
      .build();

  private AbstractCompiler compiler;

  private AggressiveDIOptimizerInfo aggressiveDIOptimizerInfo;


  public AggressiveDIOptimizerInfoCollector(AbstractCompiler compiler,
      AggressiveDIOptimizerInfo aggressiveDIOptimizerInfo) {
    this.compiler = compiler;
    this.aggressiveDIOptimizerInfo = aggressiveDIOptimizerInfo;
  }


  private interface MarkerProcessor {
    public void processMarker(NodeTraversal t, Node n, Node parent);
  }


  private final class MarkerProcessorFactory {
    private PrototypeMarkerProcessor prototypeMarkerProcessor;

    private ModuleOrConstructorMarkerProcessor moduleOrConstructorMarkerProcessor;

    private ModuleInitializerMarkerProcessor moduleInitializerProcessor;

    private InjectMarkerProcessor injectionMarkerProcessor;


    public MarkerProcessorFactory() {
      this.prototypeMarkerProcessor = new PrototypeMarkerProcessor();
      this.moduleOrConstructorMarkerProcessor = new ModuleOrConstructorMarkerProcessor();
      this.moduleInitializerProcessor = new ModuleInitializerMarkerProcessor();
      this.injectionMarkerProcessor = new InjectMarkerProcessor();
    }


    public MarkerProcessor getProperMarkerProcessor(NodeTraversal t, Node n) {
      if (n.isAssign()) {
        if (n.getFirstChild().isGetProp()) {
          String qualifiedName = n.getFirstChild().getQualifiedName();
          if (qualifiedName != null) {
            if (qualifiedName.indexOf("." + DIConsts.PROTOTYPE) > -1) {
              Node constructorNameNode = NodeUtil.getPrototypeClassName(n.getFirstChild());
              qualifiedName = constructorNameNode.getQualifiedName();
              if (aggressiveDIOptimizerInfo.getConstructorInfo(qualifiedName) != null
                  || aggressiveDIOptimizerInfo.getModuleInfo(qualifiedName) != null) {
                return this.prototypeMarkerProcessor;
              }
            }
          }
        }
      } else if (n.isFunction()) {
        JSDocInfo info = NodeUtil.getBestJSDocInfo(n);
        if (info != null && info.isConstructor()) {
          return this.moduleOrConstructorMarkerProcessor;
        }
      } else if (n.isCall()) {
        if (n.getFirstChild().isGetProp()) {
          String qualifiedName = n.getFirstChild().getQualifiedName();
          if (qualifiedName != null) {
            if (qualifiedName.equals(DIConsts.MODULE_INIT_CALL)) {
              return this.moduleInitializerProcessor;
            } else if (qualifiedName.equals(DIConsts.INJECT_CALL)) {
              return this.injectionMarkerProcessor;
            }
          }
        }
      }
      return null;
    }
  }


  private final class ModuleOrConstructorMarkerProcessor implements MarkerProcessor {

    @Override
    public void processMarker(NodeTraversal t, Node n, Node parent) {
      ConstructorInfo constructorInfo = null;
      if (NodeUtil.isFunctionDeclaration(n)) {
        String name = n.getFirstChild().getString();
        constructorInfo = new ConstructorInfo(name);
      } else {
        if (parent.isAssign()) {
          String name = parent.getFirstChild().getQualifiedName();
          constructorInfo = new ConstructorInfo(name);
        } else if (NodeUtil.isVarDeclaration(parent)) {
          constructorInfo = new ConstructorInfo(parent.getString());
        }
      }

      if (constructorInfo != null) {
        Node paramList = n.getFirstChild().getNext();
        constructorInfo.setJSDocInfo(NodeUtil.getBestJSDocInfo(n));
        constructorInfo.setConstructorNode(n);
        for (Node param : paramList.children()) {
          constructorInfo.addParam(param.getString());
        }

        ConstructorInfo duplicateEntry = aggressiveDIOptimizerInfo
            .getConstructorInfo(constructorInfo.getConstructorName());
        if (duplicateEntry != null) {
          constructorInfo.setDuplicated(true);
        }

        aggressiveDIOptimizerInfo.putConstructorInfo(constructorInfo);
      }
    }
  }


  private final class ModuleInitializerMarkerProcessor implements MarkerProcessor {
    public void processMarker(NodeTraversal t, Node n, Node parent) {
      Node maybeConfig = n.getFirstChild().getNext();

      if (maybeConfig != null) {
        List<String> moduleList = Lists.newArrayList();

        if (maybeConfig.isArrayLit()) {

          for (Node module : maybeConfig.children()) {
            if ((module.isGetProp() || module.isName()) && module.getQualifiedName() != null) {
              moduleList.add(module.getQualifiedName());
            }
          }

          if (moduleList.isEmpty()) {
            t.report(n,
                MESSAGE_FIRST_ARGUMENT_OF_MODULE_INITIALIZER_IS_EMPTY);
          }

          Node closure = maybeConfig.getNext();

          if (closure != null && closure.isFunction()) {
            Node paramList = NodeUtil.getFunctionParameters(closure);
            Node injector = paramList.getFirstChild();

            if (injector != null) {
              ModuleInitializerInfo moduleInitializerInfo = new ModuleInitializerInfo();
              moduleInitializerInfo.setConfigModuleList(moduleList);
              moduleInitializerInfo.setModuleInitCall(closure);
              aggressiveDIOptimizerInfo.addModuleInitInfo(moduleInitializerInfo);

              ModuleInitializerClosureScopeInspector inspector =
                  new ModuleInitializerClosureScopeInspector(moduleInitializerInfo);
              ClosureScopeCallback callback = new ClosureScopeCallback(injector, inspector);
              NodeTraversal.traverseRoots(compiler, Lists.newArrayList(closure), callback);
            }
          } else {
            t.report(n,
                MESSAGE_SECOND_ARGUMENT_OF_MODULE_INITIALIZER_IS_INVALID);
          }

        } else {
          t.report(n,
              MESSAGE_FIRST_ARGUMENT_OF_MODULE_INITIALIZER_IS_INVALID);
        }
      } else {
        t.report(n,
            MESSAGE_FIRST_ARGUMENT_OF_MODULE_INITIALIZER_IS_INVALID);
      }
    }
  }


  private final class InjectMarkerProcessor implements MarkerProcessor {

    public void processMarker(NodeTraversal t, Node n, Node parent) {
      Node constructorNameNode = n.getFirstChild().getNext();
      Node methodNameNode = constructorNameNode.getNext();
      String methodName = getSetterName(methodNameNode);
      String constructorName = getConstructorName(constructorNameNode);
      if (constructorName != null && methodName != null) {
        parseMethodInjectionInfo(t, methodNameNode, constructorNameNode, constructorName,
            methodName);
        Node setterNameNode = constructorNameNode.getNext();
        while (setterNameNode != null) {
          setterNameNode = setterNameNode.getNext();
          methodName = getSetterName(setterNameNode);
          if (methodName != null) {
            parseMethodInjectionInfo(t, setterNameNode, constructorNameNode, constructorName,
                methodName);
          } else {
            break;
          }
        }
        DIProcessor.detachStatement(n);
      } else {
        if (constructorName == null) {
          t.report(n, MESSAGE_FIRST_ARGUMENT_OF_INJECT_IS_INVALID);
        }
        if (methodName == null) {
          t.report(n, MESSAGE_SECOND_ARGUMENT_OF_INJECT_IS_INVALID);
        }
      }
    }


    private void parseMethodInjectionInfo(
        NodeTraversal t,
        Node n,
        Node constructorNameNode,
        String constructorName,
        String methodName) {
      MethodInjectionInfo methodInjectionInfo = null;
      if (methodName.indexOf("(") > -1) {
        Matcher matcher = DIConsts.INJECTON_PARSE_REG.matcher(methodName);
        if (matcher.find()) {
          if (matcher.groupCount() == 2) {
            String paramStr = matcher.group(2);

            if (!Strings.isNullOrEmpty(paramStr)) {
              methodName = matcher.group(1).trim();
              String[] params = paramStr.split(",");
              List<String> parameterList = Lists.newArrayList();

              for (String param : params) {
                parameterList.add(param.trim());
              }

              if (methodName.equals("constructor")) {
                ConstructorInfo constructorInfo = aggressiveDIOptimizerInfo
                    .getConstructorInfo(constructorName);
                if (constructorInfo == null) {
                  constructorInfo = new ConstructorInfo(constructorName);
                  constructorInfo.setConstructorNode(constructorNameNode);
                  aggressiveDIOptimizerInfo.putConstructorInfo(constructorInfo);
                }

                if (!constructorInfo.hasConstructorInjectionSpecification()) {
                  constructorInfo.setParamList(parameterList);
                  constructorInfo.setConstructorInjectionSpecification(true);
                } else {
                  t.report(n, MESSAGE_INJECTION_IS_ALREADY_SPECIFIED, methodName, constructorName);
                }
              } else if (!aggressiveDIOptimizerInfo.hasMethodInjectionInfo(constructorName,
                  methodName)) {
                methodInjectionInfo = new MethodInjectionInfo(matcher.group(1).trim(), n);
                methodInjectionInfo.setParameterList(parameterList);
                aggressiveDIOptimizerInfo.putMethodInjectionInfo(constructorName,
                    methodInjectionInfo);
              } else {
                t.report(n, MESSAGE_INJECTION_IS_ALREADY_SPECIFIED, methodName, constructorName);
              }
              return;
            } else {
              t.report(n, MESSAGE_INVALID_METHOD_INJECTION_SPECIFICATION, methodName);
            }
          } else {
            t.report(n, MESSAGE_INVALID_METHOD_INJECTION_SPECIFICATION, methodName);
          }
        } else {
          t.report(n, MESSAGE_INVALID_METHOD_INJECTION_SPECIFICATION, methodName);
        }
      } else {
        methodInjectionInfo = new MethodInjectionInfo(methodName, n);
        aggressiveDIOptimizerInfo.putMethodInjectionInfo(constructorName, methodInjectionInfo);
      }
    }


    private String getConstructorName(Node constructorNameNode) {
      if (constructorNameNode != null) {
        String constructorName = constructorNameNode.getQualifiedName();
        if (!Strings.isNullOrEmpty(constructorName)) {
          return constructorName;
        }
      }
      return null;
    }


    private String getSetterName(Node setterNameNode) {
      if (setterNameNode != null && setterNameNode.isString()) {
        String name = setterNameNode.getString();
        if (!Strings.isNullOrEmpty(name)) {
          return name;
        }
      }
      return null;
    }
  }


  private final class PrototypeMarkerProcessor implements MarkerProcessor {

    public void processMarker(NodeTraversal t, Node n, Node parent) {
      Node lvalue = n.getFirstChild();
      Node rvalue = lvalue.getNext();
      if (lvalue.isName() || NodeUtil.isGet(lvalue)) {
        collectPrototype(lvalue, rvalue);
      }
    }


    private void collectPrototype(Node lvalue, Node rvalue) {
      String qualifiedName = lvalue.getQualifiedName();
      if (!Strings.isNullOrEmpty(qualifiedName)) {
        if (qualifiedName.indexOf("." + DIConsts.PROTOTYPE) > -1) {
          String constructorName = NodeUtil.getPrototypeClassName(lvalue).getQualifiedName();
          // foo.prototype.bar = function() {...
          if (rvalue.isFunction() && qualifiedName.matches(DIConsts.PROTOTYPE_REGEX)) {
            String methodName = NodeUtil.getPrototypePropertyName(lvalue);
            addPrototypeMember(constructorName, methodName, rvalue);
          } else if (qualifiedName.endsWith("." + DIConsts.PROTOTYPE)
              && rvalue.isObjectLit()) {
            // foo.prototype = {...
            processAssignmentOfObjectLiteral(rvalue, constructorName);
          }
        }
      }
    }


    private void processAssignmentOfObjectLiteral(Node rvalue, String constructorName) {
      Node child = rvalue.getFirstChild();
      Node function;
      String propertyName;
      for (; child != null; child = child.getNext()) {
        if (child.isStringKey()) {
          propertyName = child.getString();
          function = child.getFirstChild();
          if (function.isFunction()) {
            addPrototypeMember(constructorName, propertyName, function);
          }
        }
      }
    }


    private void addPrototypeMember(String constructorName, String methodName, Node function) {
      Map<String, PrototypeInfo> duplicatedInfoMap = aggressiveDIOptimizerInfo
          .getPrototypeInfo(constructorName);
      PrototypeInfo duplicatedInfo;
      List<String> dupParamList = null;
      if (duplicatedInfoMap != null) {
        duplicatedInfo = duplicatedInfoMap.get(methodName);
        if (duplicatedInfo != null) {
          dupParamList = duplicatedInfo.getParamList();
        }
      }
      PrototypeInfo prototypeInfo = new PrototypeInfo(methodName, function);
      Node paramList = function.getFirstChild().getNext();

      if (dupParamList != null) {
        if (dupParamList.size() != paramList.getChildCount()) {
          prototypeInfo.setAmbiguous(true);
        }
      }

      int index = 0;
      for (Node paramNode : paramList.children()) {
        String param = paramNode.getString();
        if (dupParamList != null) {
          if (dupParamList.size() > index) {
            if (!param.equals(dupParamList.get(index))) {
              prototypeInfo.setAmbiguous(true);
            }
          }
        }
        prototypeInfo.addParam(param);
        index++;
      }
      aggressiveDIOptimizerInfo.putPrototypeInfo(constructorName, prototypeInfo);
    }
  }


  private abstract class AbstractClosureScopeInspector {

    private ImmutableSet<String> methodList;

    private String firstArgumentClassName;


    protected AbstractClosureScopeInspector(String firstArgumentClassName,
        ImmutableSet<String> methodList) {
      this.firstArgumentClassName = firstArgumentClassName;
      this.methodList = methodList;
    }


    public abstract void process(NodeTraversal t, Node n, Node firstArguments);


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


  private final class ClosureScopeCallback extends AbstractPostOrderCallback {
    private Node firstArgument;

    private AbstractClosureScopeInspector inspector;


    public ClosureScopeCallback(
        Node firstArgument,
        AbstractClosureScopeInspector inspector) {
      this.firstArgument = firstArgument;
      this.inspector = inspector;
    }


    @Override
    public void visit(NodeTraversal t, Node n, Node parent) {
      this.inspector.checkUsage(t, n, this.firstArgument);
      if (parent.isCall()) {
        if (n.isGetProp() && n.getFirstChild().isName()) {
          if (inspector.isArgumentReferenceNode(t, n.getFirstChild(), this.firstArgument)) {
            inspector.process(t, n, this.firstArgument);
          }
        }
      }
    }
  }


  private final class ModuleConfigureInspector extends AbstractClosureScopeInspector {

    private String constructorName;


    private final class BindingBuilder {
      private BindingInfo bindingInfo;

      private NodeTraversal nodeTraversal;

      private Node node;

      private boolean hasError = false;


      public BindingBuilder(String bindingName, NodeTraversal t, Node n) {
        bindingInfo = new BindingInfo(bindingName);
        nodeTraversal = t;
        node = n;
      }


      public BindingInfo build() {
        Node callNode = buildBindingInfo(this.node);
        bindingInfo.setBindCallNode(callNode);
        if (bindingInfo.getBindingType() == null) {
          return null;
        }
        return hasError ? null : bindingInfo;
      }


      private Node buildBindingInfo(Node n) {
        Node maybeCall = n.getParent();
        Node maybeGetProp = maybeCall.getParent();
        if (maybeCall.isCall() && maybeGetProp.isGetProp()) {
          Node methodNode = maybeCall.getNext();
          if (methodNode != null) {
            maybeCall = maybeGetProp.getParent();
            if (methodNode.isString()) {
              String methodName = methodNode.getString();
              if (maybeCall.isCall()) {
                return buildChain(methodName, maybeCall);
              }
            }
          }
        }
        return maybeCall;
      }


      private Node buildChain(String methodName, Node call) {
        BindingType bindingType = AggressiveDIOptimizerInfo.bindingTypeMap.get(methodName);
        if (bindingType != null) {
          Node bindingTarget = call.getFirstChild().getNext();
          if (bindingTarget != null) {
            bindingInfo.setBindedExpressionNode(bindingTarget);
            bindingInfo.setBindingType(bindingType);
            return buildBindingInfo(call.getFirstChild());
          } else {
            hasError = true;
            String message = BINDING_ERROR_MAP.get(bindingType);
            nodeTraversal.report(call, MESSAGE_BIND_CALL_SECOND_ARGUMENT_IS_INVALID, message);
          }
        } else if (methodName.equals("as")) {
          if (bindingInfo.getBindingType() == BindingType.TO) {
            Node scopeTypeNode = call.getFirstChild().getNext();
            if (scopeTypeNode != null && scopeTypeNode.isGetProp()) {
              String qualifiedName = scopeTypeNode.getQualifiedName();
              ScopeType scopeType = AggressiveDIOptimizerInfo.scopeTypeMap.get(qualifiedName);
              if (scopeType != null) {
                bindingInfo.setScopeType(scopeType);
                return call;
              } else {
                hasError = true;
                nodeTraversal.report(call, MESSAGE_BINDING_SCOPE_TYPE_IS_INVALID);
              }
            } else {
              hasError = true;
              nodeTraversal.report(call, MESSAGE_BINDING_SCOPE_TYPE_IS_INVALID);
            }
          } else {
            hasError = true;
            nodeTraversal.report(call, MESSAGE_BINDING_SCOPE_IS_INVALID);
          }
        } else {
          hasError = true;
          if (bindingInfo.getBindingType() == null) {
            nodeTraversal.report(call, MESSAGE_BINDER_BIND_HAS_NO_SUCH_METHOD, methodName);
          } else {
            String bindChainName = AggressiveDIOptimizerInfo.bindingTypeMap.inverse().get(
                bindingInfo.getBindingType());
            nodeTraversal.report(call, MESSAGE_BINDER_BIND_CHAIN_HAS_NO_SUCH_METHOD,
                bindChainName, methodName);
          }
        }
        return null;
      }
    }


    private final class InterceptorClosureScopeInspector extends AbstractClosureScopeInspector {
      private Node methodInvocation;

      private InterceptorInfo interceptorInfo;


      public InterceptorClosureScopeInspector(Node methodInvocation, InterceptorInfo interceptorInfo) {
        super(DIConsts.METHOD_INVOCATION, METHOD_INVOCATION_METHOD_LIST);
        this.methodInvocation = methodInvocation;
        this.interceptorInfo = interceptorInfo;
      }


      public void process(NodeTraversal t, Node n, Node firstArgument) {
        Node methodNode = n.getFirstChild().getNext();
        Node callNode = n.getParent();
        String method = methodNode.getString();
        if (method != null) {
          if (method.equals(DIConsts.METHOD_INVOCATION_GET_ARGUMENTS)) {
            recordArguments(callNode);
          } else if (method.equals(DIConsts.METHOD_INVOCATION_GET_CONSTRUCTOR_NAME)) {
            recordGetClassName(callNode);
          } else if (method.equals(DIConsts.METHOD_INVOCATION_GET_METHOD_NAME)) {
            recordGetMethodName(callNode);
          } else if (method.equals(DIConsts.METHOD_INVOCATION_GET_QUALIFIED_NAME)) {
            recordGetQualifiedName(callNode);
          } else if (method.equals(DIConsts.METHOD_INVOCATION_GET_THIS)) {
            recordThis(callNode);
          } else if (method.equals(DIConsts.METHOD_INVOCATION_PROCEED)) {
            recordProceed(callNode);
          }
        }
      }


      private void recordProceed(Node callNode) {
        this.interceptorInfo.addProceedNode(callNode);
      }


      private void recordThis(Node callNode) {
        this.interceptorInfo.addThisNode(callNode);
      }


      private void recordGetQualifiedName(Node callNode) {
        this.interceptorInfo.recordMethodNameAccess(true);
        this.interceptorInfo.recordConstructorNameAccess(true);
        this.interceptorInfo.addQualifiedNameNode(callNode);
      }


      private void recordGetMethodName(Node callNode) {
        this.interceptorInfo.recordMethodNameAccess(true);
        this.interceptorInfo.addMethodNameNode(callNode);
      }


      private void recordGetClassName(Node callNode) {
        this.interceptorInfo.recordConstructorNameAccess(true);
        this.interceptorInfo.addConstructorNameNode(callNode);
      }


      private void recordArguments(Node callNode) {
        this.interceptorInfo.addArgumentsNode(callNode);
      }
    }


    public ModuleConfigureInspector(String constructorName) {
      super(DIConsts.BINDER, BINDER_METHOD_SET);
      this.constructorName = constructorName;
    }


    @Override
    public void process(NodeTraversal t, Node n, Node firstArgument) {
      String qualifiedName = n.getQualifiedName();
      String binderName = firstArgument.getString();
      if (qualifiedName != null) {
        if (qualifiedName.equals(binderName + "." + DIConsts.BIND)) {
          caseBind(t, n, firstArgument);
        } else if (qualifiedName.equals(binderName + "." + DIConsts.BIND_INTERCEPTOR)) {
          caseInterceptor(t, n, firstArgument);
        }
      }
    }


    private boolean isBindingCalledInConditional(NodeTraversal t, Node n, Node firstArgument) {
      Node block = NodeUtil.getFunctionBody(firstArgument.getParent().getParent());
      Preconditions.checkNotNull(block);
      while (n != null && !n.equals(block)) {
        n = n.getParent();
        if (n.isAnd() || n.isIf() || n.isOr() || n.isSwitch()) {
          return true;
        }
      }
      return false;
    }


    private void caseBind(NodeTraversal t, Node n, Node firstArgument) {
      Node bindNameNode = n.getNext();
      if (bindNameNode != null && bindNameNode.isString()) {
        String bindingName = bindNameNode.getString();
        BindingInfo bindingInfo = new BindingBuilder(bindingName, t, n).build();
        if (bindingInfo != null) {
          if (aggressiveDIOptimizerInfo.hasBindingInfo(constructorName, bindingName)) {
            List<BindingInfo> duplicated = aggressiveDIOptimizerInfo.getBindingInfoMap(
                constructorName)
                .get(bindingName);
            if (duplicated.size() > 0) {
              if (bindingInfo.getBindingType() == BindingType.TO) {
                t.report(n, MESSAGE_CONSTRUCTOR_BINDING_DEFINITION_IS_AMBIGUOUS, bindingName,
                    constructorName);
                return;
              } else if (!isBindingListHasSameAttributes(duplicated, bindingInfo)) {
                t.report(n, MESSAGE_BINDING_DEFINITION_IS_AMBIGUOUS, bindingName, constructorName);
                return;
              }
            }
          }
          bindingInfo.setInConditional(isBindingCalledInConditional(t, n, firstArgument));
          aggressiveDIOptimizerInfo.putBindingInfo(constructorName, bindingInfo);
        }
      } else {
        t.report(n, MESSAGE_BIND_CALL_FIRST_ARGUMENT_IS_INVALID);
      }
    }


    private boolean isBindingListHasSameAttributes(
        List<BindingInfo> duplicatedList,
        BindingInfo bindingInfo) {
      for (BindingInfo duplicatedInfo : duplicatedList) {
        if (!duplicatedInfo.hasSameAttributes(bindingInfo)) {
          return false;
        }
      }
      return true;
    }


    private void caseInterceptor(NodeTraversal t, Node n, Node firstArgument) {
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
            Node paramList = interceptor.getFirstChild().getNext();
            Node methodInvocation = paramList.getFirstChild();
            if (methodInvocation != null) {
              interceptorInfo.setInConditional(isBindingCalledInConditional(t, n, firstArgument));
              interceptorInfo.setInterceptorCallNode(n.getParent());
              InterceptorClosureScopeInspector inspector = new InterceptorClosureScopeInspector(
                  methodInvocation, interceptorInfo);
              ClosureScopeCallback callback = new ClosureScopeCallback(methodInvocation, inspector);
              NodeTraversal.traverseRoots(compiler, Lists.newArrayList(interceptor), callback);
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
        Node methodMatcher, Node interceptor) {
      InterceptorInfo interceptorInfo = new InterceptorInfo();
      String classMatchTypeCallName = classMatcher.getQualifiedName();
      this.setClassMatchType(t, classMatchTypeCallName, classMatcher, interceptorInfo);
      String methodMatchTypeCallName = methodMatcher.getQualifiedName();
      this.setMethodMatchType(t, methodMatchTypeCallName, methodMatcher, interceptorInfo);
      interceptorInfo.setInterceptorNode(interceptor);
      aggressiveDIOptimizerInfo.putInterceptorInfo(this.constructorName, interceptorInfo);
      return interceptorInfo;
    }


    private void setClassMatchType(NodeTraversal t, String matchTypeCallName, Node classMatcher,
        InterceptorInfo interceptorInfo) {
      Node node = classMatcher.getNext();
      if (node != null) {
        if (matchTypeCallName.equals(DIConsts.CLASS_MATCHERS_IN_NAMESPACE)) {
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
        } else if (matchTypeCallName.equals(DIConsts.CLASS_MATCHERS_SUBCLASS_OF)) {
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
        } else if (matchTypeCallName.equals(DIConsts.CLASS_MATCHERS_IN_SUBNAMESPACE)) {
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
        } else if (matchTypeCallName.equals(DIConsts.CLASS_MATCHERS_INSTANCE_OF)) {
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
        } else if (matchTypeCallName.equals(DIConsts.MATCHERS_ANY)) {
          t.report(classMatcher, MESSAGE_MATCHER_ANY_HAS_NO_ARGUMENT);
        } else {
          t.report(classMatcher, MESSAGE_BIND_INTERCEPTOR_FIRST_ARGUMENT_IS_INVALID);
        }
      } else {
        if (matchTypeCallName.equals(DIConsts.MATCHERS_ANY)) {
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
        if (matchTypeCallName.equals(DIConsts.METHOD_MATCHER_LIKE)) {
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
        } else if (matchTypeCallName.equals(DIConsts.MATCHERS_ANY)) {
          t.report(classMatcher, MESSAGE_MATCHER_ANY_HAS_NO_ARGUMENT);
        } else {
          t.report(node, MESSAGE_BIND_INTERCEPTOR_SECOND_ARGUMENT_IS_INVALID);
        }
      } else if (matchTypeCallName.equals(DIConsts.MATCHERS_ANY)) {
        interceptorInfo.setMethodMatchType(MethodMatchType.ANY);
      } else {
        t.report(node, MESSAGE_BIND_INTERCEPTOR_SECOND_ARGUMENT_IS_INVALID);
      }
    }
  }


  private final class ModuleInitializerClosureScopeInspector extends AbstractClosureScopeInspector {
    private ModuleInitializerInfo moduleInitializerInfo;


    public ModuleInitializerClosureScopeInspector(ModuleInitializerInfo moduleInitializerInfo) {
      super(DIConsts.INJECTOR, INJECTOR_METHOD_SET);
      this.moduleInitializerInfo = moduleInitializerInfo;
    }


    @Override
    public void process(NodeTraversal t, Node n, Node firstArgument) {
      String name = n.getQualifiedName();
      String injector = firstArgument.getString();
      InjectorInfo injectorInfo = null;
      if (name.equals(injector + "." + DIConsts.GET_INSTANCE)) {
        Node classNode = n.getNext();
        if (classNode != null) {
          String constructorName = classNode.getQualifiedName();
          if (constructorName != null) {
            injectorInfo = new InjectorInfo(n.getParent(), constructorName, false);
          } else {
            t.report(n, MESSAGE_GET_INSTANCE_TARGET_INVALID);
          }
        } else {
          t.report(n, MESSAGE_GET_INSTANCE_TARGET_INVALID);
        }
      } else if (name.equals(injector + "." + DIConsts.GET_INSTANCE_BY_NAME)) {
        Node stringNode = n.getNext();
        if (stringNode != null && stringNode.isString()) {
          String bindingName = stringNode.getString();
          if (bindingName != null) {
            injectorInfo = new InjectorInfo(n.getParent(), bindingName, true);
          } else {
            t.report(n, MESSAGE_GET_INSTANCE_TARGET_INVALID);
          }
        } else {
          t.report(n, MESSAGE_GET_INSTANCE_TARGET_INVALID);
        }
      }

      if (injectorInfo != null) {
        this.moduleInitializerInfo.addInjectorInfo(injectorInfo);
      }

    }
  }


  private final class InformationIntegrator {

    public void integrate() {
      bindClassInfo();
      bindModuleInfo();
      bindBindingInfo();
      propagateModuleInfo();
    }


    private void bindClassInfo() {
      Map<String, ConstructorInfo> constructorInfoMap = aggressiveDIOptimizerInfo
          .getConstructorInfoMap();
      for (String constructorName : constructorInfoMap.keySet()) {
        ConstructorInfo constructorInfo = constructorInfoMap.get(constructorName);
        boolean isModule = propagateBaseTypeInfo(constructorInfo);
        Map<String, PrototypeInfo> prototypeInfoMap = aggressiveDIOptimizerInfo
            .getPrototypeInfo(constructorName);

        if (aggressiveDIOptimizerInfo.hasSetter(constructorName)) {
          List<MethodInjectionInfo> methodInjectionInfoList = aggressiveDIOptimizerInfo
              .getMethodInjectionInfoList(constructorName);
          constructorInfo.setMethodInjectionInfoList(methodInjectionInfoList);
          for (MethodInjectionInfo methodInjectionInfo : methodInjectionInfoList) {
            if (!methodInjectionInfo.isParameterSpecified()) {
              if (prototypeInfoMap != null) {
                PrototypeInfo prototypeInfo = prototypeInfoMap.get(methodInjectionInfo
                    .getMethodName());
                if (prototypeInfo != null) {
                  if (prototypeInfo.isAmbiguous()) {
                    DIProcessor.report(compiler, prototypeInfo.getFunction(),
                        MESSAGE_PROTOTYPE_FUNCTION_IS_AMBIGUOUS, prototypeInfo.getMethodName());
                    continue;
                  }
                } else {
                  DIProcessor.report(compiler, constructorInfo.getConstructorNode(),
                      MESSAGE_INJECTION_TARGET_NOT_EXISTS_OR_PARSABLE,
                      methodInjectionInfo.getMethodName(),
                      constructorInfo.getConstructorName());
                  continue;
                }
                methodInjectionInfo.setParameterList(prototypeInfo.getParamList());
              } else {
                DIProcessor.report(compiler, constructorInfo.getConstructorNode(),
                    MESSAGE_INJECTION_TARGET_NOT_EXISTS_OR_PARSABLE,
                    methodInjectionInfo.getMethodName(), constructorInfo.getConstructorName());
              }
            }
          }
        }

        if (prototypeInfoMap != null) {
          constructorInfo.setPrototypeInfoMap(prototypeInfoMap);
        }

        if (isModule) {
          processModule(constructorInfo);
        }
      }
    }


    private boolean propagateBaseTypeInfo(ConstructorInfo constructorInfo) {
      Map<String, PrototypeInfo> prototypeInfoMap =
          aggressiveDIOptimizerInfo.getPrototypeInfo(constructorInfo.getConstructorName());
      if (prototypeInfoMap == null) {
        prototypeInfoMap = Maps.newHashMap();
      }

      JSDocInfo info = constructorInfo.getJSDocInfo();
      boolean isModule = false;

      for (ConstructorInfo baseTypeInfo : getBaseTypeList(constructorInfo)) {
        Map<String, PrototypeInfo> basePrototypeInfoMap = aggressiveDIOptimizerInfo
            .getPrototypeInfo(baseTypeInfo.getConstructorName());
        if (basePrototypeInfoMap != null) {
          for (String baseMethodName : basePrototypeInfoMap.keySet()) {
            if (!prototypeInfoMap.containsKey(baseMethodName)) {
              prototypeInfoMap.put(baseMethodName,
                  (PrototypeInfo) basePrototypeInfoMap.get(baseMethodName).clone());
            }

            constructorInfo.addParent(baseTypeInfo);

            JSDocInfo baseJSDocInfo = baseTypeInfo.getJSDocInfo();
            isModule = isModule(baseJSDocInfo);
          }
        }
      }

      constructorInfo.setPrototypeInfoMap(prototypeInfoMap);

      return isModule || (isModule(info));
    }


    private boolean isModule(JSDocInfo info) {
      if (info != null && info.isConstructor() && info.getImplementedInterfaceCount() > 0) {
        List<JSTypeExpression> typeList = info.getImplementedInterfaces();
        for (JSTypeExpression jsType : typeList) {
          Node typeNode = jsType.getRoot();
          if (typeNode != null && typeNode.getType() == Token.BANG) {
            typeNode = typeNode.getFirstChild();
            if (typeNode.isString() && typeNode.getString() != null
                && typeNode.getString().equals(DIConsts.MODULE_INTERFACE)) {
              return true;
            }
          }
        }
      }
      return false;
    }


    private void processModule(ConstructorInfo constructorInfo) {
      Node n = constructorInfo.getConstructorNode();
      Node parent = n.getParent();
      ModuleInfo moduleInfo = null;
      if (parent.isAssign()) {
        moduleInfo = new ModuleInfo();
        moduleInfo.setModuleName(parent.getFirstChild().getQualifiedName());
      } else if (NodeUtil.isFunctionDeclaration(n)) {
        moduleInfo = new ModuleInfo();
        moduleInfo.setModuleName(n.getFirstChild().getString());
      } else if (NodeUtil.isVarDeclaration(parent)) {
        moduleInfo = new ModuleInfo();
        moduleInfo.setModuleName(parent.getString());
      }

      if (moduleInfo != null) {
        aggressiveDIOptimizerInfo.putModuleInfo(moduleInfo);

        if (constructorInfo.getParentList().size() == 0) {
          PrototypeInfo prototypeInfo = constructorInfo.getPrototypeInfo("configure");

          if (prototypeInfo != null) {
            Node paramList = NodeUtil.getFunctionParameters(prototypeInfo.getFunction());
            if (paramList.getChildCount() > 0) {
              ModuleConfigureInspector inspector =
                  new ModuleConfigureInspector(
                      constructorInfo.getConstructorName());
              ClosureScopeCallback callback = new ClosureScopeCallback(paramList.getFirstChild(),
                  inspector);
              NodeTraversal.traverseRoots(compiler,
                  Lists.newArrayList(prototypeInfo.getFunction()),
                  callback);
              moduleInfo.setModuleMethodNode(prototypeInfo.getFunction());
            }
          }
        }
      }
    }


    private List<ConstructorInfo> getBaseTypeList(ConstructorInfo constructorInfo) {
      List<ConstructorInfo> classInfoList = Lists.newArrayList();
      while (true) {
        JSDocInfo jsDocInfo = constructorInfo.getJSDocInfo();
        if (jsDocInfo != null) {
          JSTypeExpression type = jsDocInfo.getBaseType();
          if (type != null) {
            Node typeRoot = type.getRoot();
            Node firstChild = typeRoot.getFirstChild();
            if (firstChild.isString()) {
              String baseType = firstChild.getString();
              ConstructorInfo baseInfo = aggressiveDIOptimizerInfo.getConstructorInfo(baseType);
              if (baseInfo != null) {
                classInfoList.add(baseInfo);
                constructorInfo = baseInfo;
                continue;
              }
            }
          }
        }
        break;
      }
      return classInfoList;
    }


    private void bindModuleInfo() {
      Map<String, ModuleInfo> moduleInfoMap = aggressiveDIOptimizerInfo.getModuleInfoMap();
      for (String constructorName : moduleInfoMap.keySet()) {
        ModuleInfo moduleInfo = moduleInfoMap.get(constructorName);
        Map<String, PrototypeInfo> prototypeInfoMap = aggressiveDIOptimizerInfo
            .getPrototypeInfo(constructorName);
        if (prototypeInfoMap != null) {
          for (String methodName : prototypeInfoMap.keySet()) {
            PrototypeInfo prototypeInfo = prototypeInfoMap.get(methodName);
            if (methodName.equals(DIConsts.MODULE_SETUP_METHOD_NAME)) {
              Node function = prototypeInfo.getFunction();
              moduleInfo.setModuleMethodNode(function);
            }
          }
        }
      }
    }


    private void bindBindingInfo() {
      Map<String, ModuleInfo> moduleInfoMap = aggressiveDIOptimizerInfo.getModuleInfoMap();
      for (String constructorName : moduleInfoMap.keySet()) {
        ModuleInfo moduleInfo = moduleInfoMap.get(constructorName);

        if (aggressiveDIOptimizerInfo.hasBindingInfo(constructorName)) {
          ArrayListMultimap<String, BindingInfo> bindingInfoMap = aggressiveDIOptimizerInfo
              .getBindingInfoMap(constructorName);
          moduleInfo.setBindingInfoMap(bindingInfoMap);
          for (BindingInfo bindingInfo : bindingInfoMap.values()) {
            bindingInfo.setModuleName(constructorName);
          }
        }

        List<InterceptorInfo> interceptorInfoList = aggressiveDIOptimizerInfo
            .getInterceptorInfo(constructorName);
        if (interceptorInfoList != null) {
          moduleInfo.setInterceptorInfoList(interceptorInfoList);
        }
      }
    }
    
    private void propagateModuleInfo() {
      Map<String, ModuleInfo> moduleInfoMap = aggressiveDIOptimizerInfo.getModuleInfoMap();
      for (ModuleInfo moduleInfo : moduleInfoMap.values()) {
        String moduleName = moduleInfo.getModuleName();
        ConstructorInfo constructorInfo = aggressiveDIOptimizerInfo.getConstructorInfo(moduleName);
        if (constructorInfo != null && constructorInfo.getParentList().size() > 0) {
          for (ConstructorInfo parentInfo : constructorInfo.getParentList()) {
            String name = parentInfo.getConstructorName();
            ModuleInfo parentModuleInfo = moduleInfoMap.get(name);
            ArrayListMultimap<String, BindingInfo> bindingInfoMap = moduleInfo.getBindingInfoMap();
            ArrayListMultimap<String, BindingInfo> parentBindingInfoMap = parentModuleInfo.getBindingInfoMap();
            for (BindingInfo parentBindingInfo : parentBindingInfoMap.values()) {
              BindingInfo clone = (BindingInfo)parentBindingInfo.clone();
              clone.setModuleName(moduleName);
              clone.setRewrited();
              bindingInfoMap.put(clone.getName(), clone);
            }
            moduleInfo.setBindingInfoMap(bindingInfoMap);
          }
        }
      }
    }
  }


  public void collectInfo(Node root) {
    NodeTraversal.traverse(compiler, root, new CollectCallback());
    new InformationIntegrator().integrate();
    NodeTraversal.traverse(compiler, root, new InjectionAliasFinder());
  }


  private final class InjectionAliasFinder extends AbstractPostOrderCallback {
    @Override
    public void visit(NodeTraversal t, Node n, Node parent) {
      if (t.getScopeDepth() == 1) {
        switch (n.getType()) {
        case Token.ASSIGN:
          this.checkAssignment(t, n);
          break;

        case Token.VAR:
          this.checkVar(t, n);
        }
      }
    }


    private void checkAssignment(NodeTraversal t, Node n) {
      Node child = n.getFirstChild();
      String qualifiedName = child.getQualifiedName();

      if (qualifiedName != null) {

        Node rvalue = child.getNext();
        if (NodeUtil.isGet(rvalue) || rvalue.isName()) {
          String name = rvalue.getQualifiedName();
          ConstructorInfo info = aggressiveDIOptimizerInfo.getConstructorInfo(name);
          if (info != null) {
            this.createAliasClassInfoFrom(n, info, qualifiedName);
          }
        }
      }
    }


    private void createAliasClassInfoFrom(Node aliasPoint, ConstructorInfo info, String name) {
      if (aggressiveDIOptimizerInfo.getConstructorInfo(name) == null) {
        ConstructorInfo aliasInfo = new ConstructorInfo(name);
        aggressiveDIOptimizerInfo.putConstructorInfo(aliasInfo);
        info.setAliasPoint(aliasPoint);
        info.setHasInstanceFactory();
        aliasInfo.setConstructorNode(info.getConstructorNode());
        aliasInfo.setPrototypeInfoMap(info.getPrototypeInfoMap());
        aliasInfo.setJSDocInfo(info.getJSDocInfo());
        aliasInfo.setMethodInjectionInfoList(info.getMethodInjectionList());
        aliasInfo.setAliasPoint(aliasPoint);
      }
    }


    private void checkVar(NodeTraversal t, Node n) {
      Node nameNode = n.getFirstChild();
      Node rvalue = nameNode.getFirstChild();
      if (rvalue != null && (rvalue.isName() || NodeUtil.isGet(rvalue))) {
        String name = rvalue.getQualifiedName();
        ConstructorInfo info = aggressiveDIOptimizerInfo.getConstructorInfo(name);
        if (info != null) {
          createAliasClassInfoFrom(n, info, nameNode.getString());
        }
      }
    }
  }


  private final class CollectCallback extends AbstractPostOrderCallback {

    private MarkerProcessorFactory factory = new MarkerProcessorFactory();


    @Override
    public void visit(NodeTraversal t, Node n, Node parent) {
      MarkerProcessor markerProcessor = factory.getProperMarkerProcessor(t, n);
      if (markerProcessor != null) {
        markerProcessor.processMarker(t, n, parent);
      }
    }
  }
}
