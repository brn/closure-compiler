package com.google.javascript.jscomp;

import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.google.common.base.Strings;
import com.google.javascript.jscomp.DIConsts.ClassMatchType;
import com.google.javascript.jscomp.DIConsts.MethodMatchType;
import com.google.javascript.jscomp.DIInfo.BindingInfo;
import com.google.javascript.jscomp.DIInfo.BindingType;
import com.google.javascript.jscomp.DIInfo.ClassInfo;
import com.google.javascript.jscomp.DIInfo.InjectorInfo;
import com.google.javascript.jscomp.DIInfo.InterceptorInfo;
import com.google.javascript.jscomp.DIInfo.ModuleInfo;
import com.google.javascript.jscomp.DIInfo.ModuleInitializerInfo;
import com.google.javascript.jscomp.DIInfo.PrototypeInfo;
import com.google.javascript.jscomp.DIInfo.ScopeType;
import com.google.javascript.jscomp.NodeTraversal.AbstractPostOrderCallback;
import com.google.javascript.jscomp.Scope.Var;
import com.google.javascript.rhino.JSDocInfo;
import com.google.javascript.rhino.JSTypeExpression;
import com.google.javascript.rhino.Node;
import com.google.javascript.rhino.Token;

final class DIInfoCollector {

  static final DiagnosticType MESSAGE_CONSTRUCTOR_FUNCTION_IS_ANBIGUOUS = DiagnosticType
      .error(
          "JSC_MSG_CONSTRUCTOR_FUNCTION_IS_ANBIGUOUS.",
          "The constructor {0} is ambiguous. Compiler cannot resolve dependencies.");

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
              DIInfo.bindingTypeMap.get(BindingType.TO)))
      .put(
          BindingType.TO_INSTANCE,
          String.format("The argument of method %s must be a expression.",
              DIInfo.bindingTypeMap.get(BindingType.TO_INSTANCE)))
      .put(
          BindingType.TO_PROVIDER,
          String.format("The argument of method %s must be a function expression.",
              DIInfo.bindingTypeMap.get(BindingType.TO_PROVIDER)))
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
              DIInfo.getScopeTypeString());

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
      .add(DIConsts.METHOD_INVOCATION_GET_CLASS_NAME)
      .add(DIConsts.METHOD_INVOCATION_GET_METHOD_NAME)
      .add(DIConsts.METHOD_INVOCATION_GET_QUALIFIED_NAME)
      .add(DIConsts.METHOD_INVOCATION_GET_ARGUMENTS)
      .add(DIConsts.METHOD_INVOCATION_PROCEED)
      .add(DIConsts.METHOD_INVOCATION_GET_THIS)
      .build();

  private AbstractCompiler compiler;

  private DIInfo dIInfo;


  public DIInfoCollector(AbstractCompiler compiler, DIInfo dIInfo) {
    this.compiler = compiler;
    this.dIInfo = dIInfo;
  }


  private interface MarkerProcessor {
    public void processMarker(NodeTraversal t, Node n, Node parent);
  }


  private final class MarkerProcessorFactory {
    private PrototypeMarkerProcessor prototypeMarkerProcessor;

    private ModuleOrConstructorMarkerProcessor moduleOrConstructorMarkerProcessor;

    private ModuleInitializerMarkerProcessor moduleInitializerProcessor;

    private InjectMarkerProcessor injectionMarkerProcessor;

    private Set<Node> visited = Sets.newHashSet();


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
              visited.add(n);
              return this.prototypeMarkerProcessor;
            }
          }
        }
      } else if (n.isFunction()) {
        JSDocInfo info = NodeUtil.getBestJSDocInfo(n);
        if (info != null && info.isConstructor()) {
          visited.add(n);
          return this.moduleOrConstructorMarkerProcessor;
        }
      } else if (n.isCall()) {
        if (n.getFirstChild().isGetProp()) {
          String qualifiedName = n.getFirstChild().getQualifiedName();
          if (qualifiedName != null) {
            if (qualifiedName.equals(DIConsts.MODULE_INIT_CALL)) {
              visited.add(n);
              return this.moduleInitializerProcessor;
            } else if (qualifiedName.equals(DIConsts.INJECT_CALL)) {
              visited.add(n);
              return this.injectionMarkerProcessor;
            }
          }
        }
      }
      return null;
    }
  }


  private final class ModuleOrConstructorMarkerProcessor implements MarkerProcessor {

    public void processMarker(NodeTraversal t, Node n, Node parent) {
      if (this.isModule(n)) {
        this.processModule(t, n);
      } else {
        this.processConstructor(t, n);
      }
    }


    private boolean isModule(Node n) {
      JSDocInfo info = NodeUtil.getBestJSDocInfo(n);
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


    private void processModule(NodeTraversal t, Node n) {
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
        dIInfo.putModuleInfo(moduleInfo);
      }
    }


    private void processConstructor(NodeTraversal t, Node n) {
      ClassInfo classInfo = null;
      if (NodeUtil.isFunctionDeclaration(n)) {
        String name = n.getFirstChild().getString();
        classInfo = new ClassInfo(name);
      } else {
        Node parent = n.getParent();
        if (parent.isAssign()) {
          String name = parent.getFirstChild().getQualifiedName();
          classInfo = new ClassInfo(name);
        } else if (NodeUtil.isVarDeclaration(parent)) {
          classInfo = new ClassInfo(parent.getString());
        }
      }

      if (classInfo != null) {
        Node paramList = n.getFirstChild().getNext();
        classInfo.setJSDocInfo(NodeUtil.getBestJSDocInfo(n));
        classInfo.setConstructorNode(n);
        for (Node param : paramList.children()) {
          classInfo.addParam(param.getString());
        }

        ClassInfo duplicateEntry = dIInfo.getClassInfo(classInfo.getClassName());
        if (duplicateEntry != null) {
          if (!duplicateEntry.getParamList().equals(classInfo.getParamList())) {
            t.report(classInfo.getConstructorNode(), MESSAGE_CONSTRUCTOR_FUNCTION_IS_ANBIGUOUS,
                classInfo.getClassName());
            return;
          }
        }

        dIInfo.putClassInfo(classInfo);
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
              dIInfo.addModuleInitInfo(moduleInitializerInfo);

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
      Node classNameNode = n.getFirstChild().getNext();
      String setterName = this.getSetterName(classNameNode.getNext());
      String className = this.getClassName(classNameNode);
      if (className != null && setterName != null) {
        dIInfo.putSetter(className, setterName);
        Node setterNameNode = classNameNode.getNext();
        while (setterNameNode != null) {
          setterNameNode = setterNameNode.getNext();
          setterName = this.getSetterName(setterNameNode);
          if (setterName != null) {
            dIInfo.putSetter(className, setterName);
          } else {
            break;
          }
        }
        DIProcessor.detachStatement(n);
      } else {
        if (className == null) {
          t.report(n, MESSAGE_FIRST_ARGUMENT_OF_INJECT_IS_INVALID);
        }
        if (setterName == null) {
          t.report(n, MESSAGE_SECOND_ARGUMENT_OF_INJECT_IS_INVALID);
        }
      }
    }


    private String getClassName(Node classNameNode) {
      if (classNameNode != null) {
        String className = classNameNode.getQualifiedName();
        if (!Strings.isNullOrEmpty(className)) {
          return className;
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
      if ((lvalue.isGetProp() || lvalue.isGetElem())) {
        this.collectPrototype(lvalue, rvalue);
      }

      Node getProp = n.getFirstChild();
      Node classNameNode = NodeUtil.getPrototypeClassName(getProp);
      String methodName = NodeUtil.getPrototypePropertyName(getProp);
      String qualifiedName = classNameNode.getQualifiedName();

      if (!Strings.isNullOrEmpty(qualifiedName) &&
          dIInfo.getModuleInfo(qualifiedName) != null &&
          methodName.equals("configure") &&
          getProp.getNext().isFunction()) {

        Node paramList = NodeUtil.getFunctionParameters(rvalue);
        if (paramList.getChildCount() > 0) {
          Node nameNode = NodeUtil.getPrototypeClassName(n.getFirstChild());
          ModuleConfigureInspector inspector = new ModuleConfigureInspector(
              nameNode.getQualifiedName());
          ClosureScopeCallback callback = new ClosureScopeCallback(paramList.getFirstChild(),
              inspector);
          NodeTraversal.traverseRoots(compiler, Lists.newArrayList(rvalue), callback);
        }
      }
    }


    private void collectPrototype(Node lvalue, Node rvalue) {
      String qualifiedName = lvalue.getQualifiedName();
      if (qualifiedName != null) {
        if (qualifiedName.indexOf("." + DIConsts.PROTOTYPE) > -1) {
          String className = NodeUtil.getPrototypeClassName(lvalue).getQualifiedName();

          // foo.prototype.bar = function() {...
          if (rvalue.isFunction() && qualifiedName.matches(DIConsts.PROTOTYPE_REGEX)) {
            String methodName = NodeUtil.getPrototypePropertyName(lvalue);
            this.addPrototypeMember(className, methodName, rvalue);
          } else if (qualifiedName.endsWith("." + DIConsts.PROTOTYPE)
              && rvalue.isObjectLit()) {
            // foo.prototype = {...
            processAssignmentOfObjectLiteral(rvalue, className);
          }
        }
      }
    }


    private void processAssignmentOfObjectLiteral(Node rvalue, String className) {
      Node child = rvalue.getFirstChild();
      Node function;
      String propertyName;
      for (; child != null; child = child.getNext()) {
        if (child.isStringKey()) {
          propertyName = child.getString();
          function = child.getFirstChild();
          if (function.isFunction()) {
            addPrototypeMember(className, propertyName, function);
          }
        }
      }
    }


    private void addPrototypeMember(String className, String methodName, Node function) {
      PrototypeInfo prototypeInfo = new PrototypeInfo(methodName, function);
      Node paramList = function.getFirstChild().getNext();
      for (Node param : paramList.children()) {
        prototypeInfo.addParam(param.getString());
      }
      dIInfo.putPrototypeInfo(className, prototypeInfo);
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

    private String className;


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
                buildChain(methodName, maybeCall);
                return maybeCall;
              }
            }
          }
        }
        return null;
      }


      private void buildChain(String methodName, Node call) {
        BindingType bindingType = DIInfo.bindingTypeMap.get(methodName);
        if (bindingType != null) {
          Node bindingTarget = call.getFirstChild().getNext();
          if (bindingTarget != null) {
            bindingInfo.setBindedExpressionNode(bindingTarget);
            bindingInfo.setBindingType(bindingType);
            buildBindingInfo(call.getFirstChild());
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
              ScopeType scopeType = DIInfo.scopeTypeMap.get(qualifiedName);
              if (scopeType != null) {
                bindingInfo.setScopeType(scopeType);
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
            String bindChainName = DIInfo.bindingTypeMap.inverse().get(
                bindingInfo.getBindingType());
            nodeTraversal.report(call, MESSAGE_BINDER_BIND_CHAIN_HAS_NO_SUCH_METHOD,
                bindChainName, methodName);
          }
        }
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
          } else if (method.equals(DIConsts.METHOD_INVOCATION_GET_CLASS_NAME)) {
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
        this.interceptorInfo.recordClassNameAccess(true);
        this.interceptorInfo.addQualifiedNameNode(callNode);
      }


      private void recordGetMethodName(Node callNode) {
        this.interceptorInfo.recordMethodNameAccess(true);
        this.interceptorInfo.addMethodNameNode(callNode);
      }


      private void recordGetClassName(Node callNode) {
        this.interceptorInfo.recordClassNameAccess(true);
        this.interceptorInfo.addClassNameNode(callNode);
      }


      private void recordArguments(Node callNode) {
        this.interceptorInfo.addArgumentsNode(callNode);
      }
    }


    public ModuleConfigureInspector(String className) {
      super(DIConsts.BINDER, BINDER_METHOD_SET);
      this.className = className;
    }


    @Override
    public void process(NodeTraversal t, Node n, Node firstArgument) {
      String qualifiedName = n.getQualifiedName();
      String binderName = firstArgument.getString();
      if (qualifiedName != null) {
        if (qualifiedName.equals(binderName + "." + DIConsts.BIND)) {
          caseBind(t, n);
        } else if (qualifiedName.equals(binderName + "." + DIConsts.BIND_INTERCEPTOR)) {
          caseInterceptor(t, n);
        }
      }
    }


    private void caseBind(NodeTraversal t, Node n) {
      Node bindNameNode = n.getNext();
      if (bindNameNode != null && bindNameNode.isString()) {
        String bindingName = bindNameNode.getString();
        BindingInfo bindingInfo = new BindingBuilder(bindingName, t, n).build();
        if (bindingInfo != null) {
          dIInfo.putBindingInfo(className, bindingInfo);
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
            Node paramList = interceptor.getFirstChild().getNext();
            Node methodInvocation = paramList.getFirstChild();
            if (methodInvocation != null) {
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
      dIInfo.putInterceptorInfo(this.className, interceptorInfo);
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
          String className = classNode.getQualifiedName();
          if (className != null) {
            injectorInfo = new InjectorInfo(n.getParent(), className, false);
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
      this.bindClassInfo();
      this.bindBaseTypeInfo();
      this.bindModuleInfo();
      this.bindBindingInfo();
    }


    private void bindClassInfo() {
      Map<String, ClassInfo> classInfoMap = dIInfo.getClassInfoMap();
      for (String className : classInfoMap.keySet()) {
        ClassInfo classInfo = classInfoMap.get(className);
        Map<String, PrototypeInfo> prototypeInfoMap = dIInfo
            .getPrototypeInfo(className);
        if (prototypeInfoMap != null) {
          classInfo.setPrototypeInfoMap(prototypeInfoMap);
        }

        if (dIInfo.hasSetter(className)) {
          classInfo.setSetterList(dIInfo.getSetterList(className));
        }
      }
    }


    private void bindBaseTypeInfo() {
      Map<String, ClassInfo> classInfoMap = dIInfo.getClassInfoMap();
      for (ClassInfo classInfo : classInfoMap.values()) {
        this.checkBaseType(classInfo);
      }
    }


    private void checkBaseType(ClassInfo classInfo) {
      Map<String, PrototypeInfo> prototypeInfoMap = classInfo.getPrototypeInfoMap();
      for (ClassInfo baseTypeInfo : this.getBaseTypeList(classInfo)) {
        Map<String, PrototypeInfo> basePrototypeInfoMap = baseTypeInfo.getPrototypeInfoMap();
        for (String baseMethodName : basePrototypeInfoMap.keySet()) {
          if (!prototypeInfoMap.containsKey(baseMethodName)) {
            prototypeInfoMap.put(baseMethodName,
                (PrototypeInfo) basePrototypeInfoMap.get(baseMethodName).clone());
          }
        }
      }
    }


    private List<ClassInfo> getBaseTypeList(ClassInfo classInfo) {
      List<ClassInfo> classInfoList = Lists.newArrayList();
      while (true) {
        JSDocInfo jsDocInfo = classInfo.getJSDocInfo();
        if (jsDocInfo != null) {
          JSTypeExpression type = jsDocInfo.getBaseType();
          if (type != null) {
            Node typeRoot = type.getRoot();
            Node firstChild = typeRoot.getFirstChild();
            if (firstChild.isString()) {
              String baseType = firstChild.getString();
              ClassInfo baseInfo = dIInfo.getClassInfo(baseType);
              if (baseInfo != null) {
                classInfoList.add(baseInfo);
                classInfo = baseInfo;
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
      Map<String, ModuleInfo> moduleInfoMap = dIInfo.getModuleInfoMap();
      for (String className : moduleInfoMap.keySet()) {
        ModuleInfo moduleInfo = moduleInfoMap.get(className);
        Map<String, PrototypeInfo> prototypeInfoMap = dIInfo
            .getPrototypeInfo(className);
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
      Map<String, ModuleInfo> moduleInfoMap = dIInfo.getModuleInfoMap();
      for (String className : moduleInfoMap.keySet()) {
        ModuleInfo moduleInfo = moduleInfoMap.get(className);
        Map<String, BindingInfo> bindingInfoMap = dIInfo.getBindingInfoMap(className);
        List<InterceptorInfo> interceptorInfoList = dIInfo.getInterceptorInfo(className);
        if (bindingInfoMap != null) {
          moduleInfo.setBindingInfoMap(bindingInfoMap);
        }
        if (interceptorInfoList != null) {
          moduleInfo.setInterceptorInfoList(interceptorInfoList);
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
          ClassInfo info = dIInfo.getClassInfo(name);
          if (info != null) {
            this.createAliasClassInfoFrom(n, info, qualifiedName);
          }
        }
      }
    }


    private void createAliasClassInfoFrom(Node aliasPoint, ClassInfo info, String name) {
      if (dIInfo.getClassInfo(name) == null) {
        ClassInfo aliasInfo = new ClassInfo(name);
        dIInfo.putClassInfo(aliasInfo);
        aliasInfo.setConstructorNode(info.getConstructorNode());
        aliasInfo.setPrototypeInfoMap(info.getPrototypeInfoMap());
        aliasInfo.setJSDocInfo(info.getJSDocInfo());
        aliasInfo.setSetterList(info.getSetterList());
        aliasInfo.setAliasPoint(aliasPoint);
      }
    }


    private void checkVar(NodeTraversal t, Node n) {
      Node nameNode = n.getFirstChild();
      Node rvalue = nameNode.getFirstChild();
      if (rvalue != null && (rvalue.isName() || NodeUtil.isGet(rvalue))) {
        String name = rvalue.getQualifiedName();
        ClassInfo info = dIInfo.getClassInfo(name);
        if (info != null) {
          this.createAliasClassInfoFrom(n, info, nameNode.getString());
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
