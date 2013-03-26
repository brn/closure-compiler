package com.google.javascript.jscomp;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.common.base.Strings;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.javascript.jscomp.NodeTraversal.AbstractNodeTypePruningCallback;
import com.google.javascript.jscomp.NodeTraversal.AbstractPostOrderCallback;
import com.google.javascript.jscomp.Scope.Var;
import com.google.javascript.rhino.JSDocInfo;
import com.google.javascript.rhino.JSDocInfoBuilder;
import com.google.javascript.rhino.JSTypeExpression;
import com.google.javascript.rhino.Node;
import com.google.javascript.rhino.Token;

class CampInjectionProcessor {

  static final DiagnosticType MESSAGE_CREATE_INSTANCE_TARGET_NOT_VALID = DiagnosticType.error(
      "JSC_MSG_CREATE_INSTANCE_TARGET_NOT_VALID.",
      "The argument of camp.dependencies.injector.createInstance must be a constructor.");

  static final DiagnosticType MESSAGE_BIND_CALL_FIRST_ARGUMENT_IS_NOT_VALID = DiagnosticType.error(
      "JSC_MSG_BIND_CALL_FIRST_ARGUMENT_IS_NOT_VALID.",
      "The first argument of camp.dependencies.injector.bind must be a string.");

  static final DiagnosticType MESSAGE_BIND_CALL_SECOND_ARGUMENT_IS_NOT_VALID = DiagnosticType
      .error("JSC_MSG_BIND_CALL_SECOND_ARGUMENT_IS_NOT_VALID.",
          "The second argument of camp.dependencies.injector.bind is required.");

  static final DiagnosticType MESSAGE_INVALID_DEPENDECY_GET_CALL = DiagnosticType.error(
      "JSC_MSG_INVALID_DEPENDECY_GET_CALL",
      "The arguments of camp.dependencies.get must be a string.");

  static final DiagnosticType MESSAGE_DEFINE_PROVIDER_FIRST_ARGUMENT_IS_NOT_VALID = DiagnosticType
      .error("JSC_MSG_DEFINE_PROVIDER_FIRST_ARGUMENT_IS_NOT_VALID.",
          "The first argument of camp.dependencies.injector.defineProvider must be a class.");

  static final DiagnosticType MESSAGE_DEFINE_PROVIDER_SECOND_ARGUMENT_IS_NOT_VALID = DiagnosticType
      .error("JSC_MSG_DEFINE_PROVIDER__SECOND_ARGUMENT_IS_NOT_VALID.",
          "The second argument of camp.dependencies.injector.defineProvider must be a function.");

  private static final String CREATE_INSTANCE_CALL = "camp.dependencies.injector.createInstance";

  private static final String BIND_CALL = "camp.dependencies.binder.bind";

  private static final String BIND_PROVIDER_CALL = "camp.dependencies.binder.bindProvider";

  private static final String BIND_INTERCEPTOR_CALL = "camp.dependencies.binder.bindInterceptor";

  private static final String INTERCEPTOR_REGISTRY = "jscomp$aop$interceptorRegistry";

  private static final String INJECT_CALL = "camp.dependencies.injector.inject";

  private static final String GET_CALL = "camp.dependencies.injector.get";

  private static final String SINGLETON_CALL = "goog.addSingletonGetter";

  private static final String PROVIDE_CALL = "goog.provide";

  private static final String PROTOTYPE = "prototype";

  private static final String PROTOTYPE_REGEX = "(.*\\.prototype\\..*|.*\\.prototype$)";

  private static final String GET_INJECTIED_INSTANCE = "jscomp$getInjectedInstance";

  private static final String INJECTED_INSTANCE = "_jscomp$injectedInstance";

  private static final String INTERCEPTOR_PREFIX = "jscomp$interceptor$";

  private static final String THIS_REFERENCE = "jscomp$methodInvocation$this";

  private static final String ARGUMENTS_REFERENCE = "jscomp$methodInvocation$arguments";

  private static final String METHOD_NAME_REFERENCE = "jscomp$methodInvocation$methodName";

  private static final String CLASS_NAME_REFERENCE = "jscomp$methodInvocation$className";

  private static final String CALL_REFERENCE = "jscomp$methodInvocation$proceed";

  private static final String RESULT_REFERENCE = "jscomp$methodInvocation$result";

  private static final String INTERCEPTOR_RESULT_REFERENCE = "jscomp$interceptor$temporary$result";

  private int interceptorId = 0;

  private InjectionTargetInfo injectionTargetInfo = new InjectionTargetInfo();

  private Map<String, ClassInfo> classInfoMap = new HashMap<String, ClassInfo>();

  private final class Pair<T, S> {
    private T first;
    private S second;

    public Pair(T first, S second) {
      this.first = first;
      this.second = second;
    }

    public T getFirst() {
      return this.first;
    }

    public S getSecond() {
      return this.second;
    }
  }

  private final class InjectionTargetInfo {
    private Map<String, Node> createInstanceTargetMap = Maps.newHashMap();

    private Map<String, Node> providerMap = Maps.newHashMap();

    private Map<String, List<String>> setterMap = Maps.newHashMap();

    private Map<String, Map<String, PrototypeInfo>> prototypeInfoMap = Maps.newHashMap();

    private Map<String, Node> singletonMap = Maps.newHashMap();

    private Map<String, Pair<Node, Node>> bindTargetMap = Maps.newHashMap();

    private Map<String, Node> getMap = Maps.newHashMap();

    private Map<String, Boolean> provideMap = Maps.newHashMap();

    private List<InterceptorInfo> interceptorInfoList = Lists.newArrayList();

    public void putCreateInstanceTarget(String name, Node n) {
      this.createInstanceTargetMap.put(name, n);
    }

    public Node getCreateInstanceTarget(String name) {
      return this.createInstanceTargetMap.get(name);
    }

    public Set<String> getCreateInstanceTargetMapIter() {
      return this.createInstanceTargetMap.keySet();
    }

    public boolean hasCreateInstanceTarget(String name) {
      return this.createInstanceTargetMap.containsKey(name);
    }

    public void putProvider(String name, Node n) {
      this.providerMap.put(name, n);
    }

    public Node getProvider(String name) {
      return this.providerMap.get(name);
    }

    public boolean hasProvider(String name) {
      return this.providerMap.containsKey(name);
    }

    public void putSetter(String className, String setterName) {
      if (!this.setterMap.containsKey(className)) {
        this.setterMap.put(className, new ArrayList<String>());
      }
      List<String> setterList = this.setterMap.get(className);
      setterList.add(setterName);
    }

    public List<String> getSetterMap(String name) {
      return this.setterMap.get(name);
    }

    public boolean hasSetter(String name) {
      return this.setterMap.containsKey(name);
    }

    public void putSingleton(String className, Node n) {
      this.singletonMap.put(className, n);
    }

    public boolean isSingleton(String className) {
      return this.singletonMap.containsKey(className);
    }

    public Node getSingletonNode(String className) {
      return this.singletonMap.get(className);
    }

    public void putPrototypeInfo(String className, PrototypeInfo prototypeInfo) {
      Map<String, PrototypeInfo> prototypeInfoList;
      if (this.prototypeInfoMap.containsKey(className)) {
        prototypeInfoList = prototypeInfoMap.get(className);
      } else {
        prototypeInfoList = Maps.newHashMap();
        this.prototypeInfoMap.put(className, prototypeInfoList);
      }
      prototypeInfoList.put(prototypeInfo.getMethodName(), prototypeInfo);
    }

    public boolean hasPrototypeInfo(String className) {
      return this.prototypeInfoMap.containsKey(className);
    }

    public Map<String, PrototypeInfo> getPrototypeInfo(String className) {
      return this.prototypeInfoMap.get(className);
    }

    public void putBindTarget(String bindingName, Node node, Node entity) {
      this.bindTargetMap.put(bindingName, new Pair<Node, Node>(entity, node));
    }

    public Pair<Node, Node> getBindTarget(String name) {
      return this.bindTargetMap.get(name);
    }

    public boolean hasBindTarget(String name) {
      return this.bindTargetMap.containsKey(name);
    }

    public Set<String> getBindTargetIter() {
      return this.bindTargetMap.keySet();
    }

    public void putGet(String name, Node n) {
      this.getMap.put(name, n);
    }

    public Iterable<String> getGetMapIter() {
      return this.getMap.keySet();
    }

    public Node getGetMap(String bindingName) {
      return this.getMap.get(bindingName);
    }

    public boolean getProvide(String name) {
      return this.provideMap.containsKey(name);
    }

    public void setProvide(String name) {
      this.provideMap.put(name, true);
    }

    public void addInterceptorInfo(InterceptorInfo interceptorInfo) {
      this.interceptorInfoList.add(interceptorInfo);
    }

    public List<InterceptorInfo> getInterceptorInfoList() {
      return this.interceptorInfoList;
    }
  }

  private final class InterceptorInfo {

    private Node interceptor;

    private String name;

    private String packageMatcher;

    private String methodMatcher;

    private boolean methodNameAccess = false;

    private boolean classNameAccess = false;

    private boolean proceedAccess = false;

    private boolean weaved = false;

    public InterceptorInfo(String name, Node interceptor, String packageMatcher,
        String methodMatcher) {
      this.name = name;
      this.interceptor = interceptor;
      this.packageMatcher = packageMatcher;
      this.methodMatcher = methodMatcher;
    }

    public String getName() {
      return this.name;
    }

    public Node getInterceptor() {
      return this.interceptor;
    }

    public String getPackageMatcher() {
      return this.packageMatcher;
    }

    public String getMethodMatcher() {
      return this.methodMatcher;
    }

    /**
     * @return the methodNameAccess
     */
    public boolean isMethodNameAccess() {
      return methodNameAccess;
    }

    /**
     * @param methodNameAccess
     *          the methodNameAccess to set
     */
    public void setMethodNameAccess(boolean methodNameAccess) {
      this.methodNameAccess = methodNameAccess;
    }

    /**
     * @return the classNameAccess
     */
    public boolean isClassNameAccess() {
      return classNameAccess;
    }

    /**
     * @param classNameAccess
     *          the classNameAccess to set
     */
    public void setClassNameAccess(boolean classNameAccess) {
      this.classNameAccess = classNameAccess;
    }

    public boolean isProceedAccess() {
      return proceedAccess;
    }

    public void setProceedAccess(boolean proceedAccess) {
      this.proceedAccess = proceedAccess;
    }

    /**
     * @return the weaved
     */
    public boolean isWeaved() {
      return weaved;
    }

    /**
     * @param weaved
     *          the weaved to set
     */
    public void setWeaved(boolean weaved) {
      this.weaved = weaved;
    }
  }

  private final class PrototypeInfo {
    private Node function;
    private Node baseFunction;
    private List<String> paramList = Lists.newArrayList();
    private String name;
    private boolean weaved = false;
    private boolean proceeded = false;

    public PrototypeInfo(String name, Node function) {
      this.function = function;
      this.baseFunction = function.cloneTree();
      this.baseFunction.copyInformationFromForTree(function);
      this.name = name;
    }

    public void addParam(String name) {
      this.paramList.add(name);
    }

    public List<String> getParamList() {
      return this.paramList;
    }

    public String getMethodName() {
      return this.name;
    }

    public Node getFunction() {
      return this.function;
    }

    public Node getBaseFunction() {
      return this.baseFunction;
    }

    public boolean isWeaved() {
      return weaved;
    }

    public void setWeaved(boolean weaved) {
      this.weaved = weaved;
    }

    public boolean isProceeded() {
      return proceeded;
    }

    public void setProceeded(boolean proceeded) {
      this.proceeded = proceeded;
    }
  }

  private class ClassInfo {
    private String className;

    private List<String> paramList = Lists.newArrayList();

    private List<String> setterList;

    private Node provider;

    private boolean isSingleton;

    private Node singletonCallNode;

    private Map<String, PrototypeInfo> prototypeInfoMap;

    public ClassInfo(String className) {
      this.className = className;
    }

    public String getClassName() {
      return this.className;
    }

    public void addParam(String name) {
      paramList.add(name);
    }

    public List<String> getParamList() {
      return this.paramList;
    }

    /**
     * @return the isSingleton
     */
    public boolean isSingleton() {
      return isSingleton;
    }

    /**
     * @param isSingleton
     *          the isSingleton to set
     */
    public void setSingleton(boolean isSingleton) {
      this.isSingleton = isSingleton;
    }

    /**
     * @return the setterList
     */
    public List<String> getSetterList() {
      return setterList;
    }

    /**
     * @param setterList
     *          the setterList to set
     */
    public void setSetterList(List<String> setterList) {
      if (this.setterList == null) {
        this.setterList = setterList;
      } else {
        this.setterList.addAll(setterList);
      }
    }

    /**
     * @return the provider
     */
    public Node getProvider() {
      return provider;
    }

    /**
     * @param provider
     *          the provider to set
     */
    public void setProvider(Node provider) {
      this.provider = provider;
    }

    /**
     * @return the prototypeInfoMap
     */
    public PrototypeInfo getPrototypeInfo(String name) {
      return prototypeInfoMap != null ? prototypeInfoMap.get(name) : null;
    }

    public Map<String, PrototypeInfo> getPrototypeInfoMap() {
      return this.prototypeInfoMap;
    }

    /**
     * @param prototypeInfoMap
     *          the prototypeInfoMap to set
     */
    public void setPrototypeInfoMap(Map<String, PrototypeInfo> prototypeInfoMap) {
      if (this.prototypeInfoMap == null) {
        this.prototypeInfoMap = prototypeInfoMap;
      } else {
        this.prototypeInfoMap.putAll(prototypeInfoMap);
      }
    }

    public Node getSingletonCallNode() {
      return singletonCallNode;
    }

    public void setSingletonCallNode(Node singletonCallNode) {
      this.singletonCallNode = singletonCallNode;
    }
  }

  private interface InjectionMarkerProcessor {
    public void processMarker();
  }

  private final class InjectionMarkerProcessorFactory {
    public InjectionMarkerProcessor create(NodeTraversal t, Node n) {
      if (n.isCall()) {
        Node maybeGetProp = n.getFirstChild();
        if (maybeGetProp.isGetProp()) {
          String qualifiedName = maybeGetProp.getQualifiedName();
          if (qualifiedName != null) {
            return doCreate(qualifiedName, t, n);
          }
        }
      } else if (n.isAssign()) {
        return new PrototypeMarkerProcessor(t, n);
      }
      return null;
    }

    private InjectionMarkerProcessor doCreate(String qualifiedName, NodeTraversal t, Node n) {
      if (qualifiedName.equals(CREATE_INSTANCE_CALL)) {
        return new CreateInstanceMarkerProcessor(t, n);
      } else if (qualifiedName.equals(BIND_CALL)) {
        // camp.dependencies.injector.bind(...)
        return new InjectionBinderMarkerProcessor(t, n);
      } else if (qualifiedName.equals(INJECT_CALL)) {
        // camp.dependencies.injector.inject(...)
        return new SetterInjectionMarkerProcessor(t, n);
      } else if (qualifiedName.equals(GET_CALL)) {
        return new InjectionGetterMarkerProcessor(t, n);
      } else if (qualifiedName.equals(BIND_PROVIDER_CALL)) {
        // camp.dependencies.injector.defineProvider(...)
        return new ProviderMarkerProcessor(t, n);
      } else if (qualifiedName.equals(BIND_INTERCEPTOR_CALL)) {
        // camp.dependencies.binder.bindInterceptor(...)
        return new InterceptorMarkerProcessor(t, n);
      } else if (qualifiedName.equals(PROVIDE_CALL)) {
        return new ExportedClassMarkerProcessor(t, n);
      } else if (qualifiedName.equals(SINGLETON_CALL)) {
        return new SingletonMarkerProcessor(t, n);
      }
      return null;
    }
  }

  private final class CreateInstanceMarkerProcessor implements InjectionMarkerProcessor {
    private Node node;

    private NodeTraversal nodeTraversal;

    public CreateInstanceMarkerProcessor(NodeTraversal t, Node n) {
      this.nodeTraversal = t;
      this.node = n;
    }

    public void processMarker() {
      Node maybeGetProp = this.node.getFirstChild().getNext();
      if (maybeGetProp == null || !maybeGetProp.isGetProp()) {
        this.nodeTraversal.report(this.node, MESSAGE_CREATE_INSTANCE_TARGET_NOT_VALID);
      }
      if (Strings.isNullOrEmpty(maybeGetProp.getQualifiedName())) {
        this.nodeTraversal.report(this.node, MESSAGE_CREATE_INSTANCE_TARGET_NOT_VALID);
      }
      injectionTargetInfo.putCreateInstanceTarget(maybeGetProp.getQualifiedName(), this.node);
    }
  }

  private final class InjectionBinderMarkerProcessor implements InjectionMarkerProcessor {

    private Node node;

    private NodeTraversal nodeTraversal;

    public InjectionBinderMarkerProcessor(NodeTraversal t, Node n) {
      this.nodeTraversal = t;
      this.node = n;
    }

    public void processMarker() {
      Node bindingNameNode = this.node.getFirstChild().getNext();
      String bindingName = bindingNameNode.isString() ? bindingNameNode.getString() : null;
      if (bindingName == null) {
        this.nodeTraversal.report(this.node, MESSAGE_BIND_CALL_FIRST_ARGUMENT_IS_NOT_VALID);
      }

      Node entityNode = bindingNameNode.getNext();
      if (entityNode != null) {
        injectionTargetInfo.putBindTarget(bindingName, this.node, entityNode);
      } else {
        this.nodeTraversal.report(entityNode, MESSAGE_BIND_CALL_SECOND_ARGUMENT_IS_NOT_VALID);
      }
    }
  }

  private final class SetterInjectionMarkerProcessor implements InjectionMarkerProcessor {
    private NodeTraversal nodeTraversal;

    private Node node;

    public SetterInjectionMarkerProcessor(NodeTraversal t, Node n) {
      this.nodeTraversal = t;
      this.node = n;
    }

    public void processMarker() {
      Node targetClass = this.node.getFirstChild().getNext();
      if (targetClass == null) {
        this.nodeTraversal.report(this.node, MESSAGE_DEFINE_PROVIDER_FIRST_ARGUMENT_IS_NOT_VALID);
      }
      Node injections = targetClass.getNext();

      if (injections == null) {
        this.nodeTraversal.report(this.node, MESSAGE_DEFINE_PROVIDER_SECOND_ARGUMENT_IS_NOT_VALID);
      }

      String qualifiedName = targetClass.getQualifiedName();
      if (qualifiedName != null) {
        while (injections != null) {
          if (injections.isString()) {
            injectionTargetInfo.putSetter(qualifiedName, injections.getString());
          }
          injections = injections.getNext();
        }
      }
      this.node.getParent().detachFromParent();
    }
  }

  private final class InjectionGetterMarkerProcessor implements InjectionMarkerProcessor {
    private Node node;

    private NodeTraversal nodeTraversal;

    public InjectionGetterMarkerProcessor(NodeTraversal t, Node n) {
      this.node = n;
      this.nodeTraversal = t;
    }

    public void processMarker() {
      Node bindingNameNode = this.node.getFirstChild().getNext();
      if (bindingNameNode == null) {
        this.nodeTraversal.report(this.node, MESSAGE_INVALID_DEPENDECY_GET_CALL);
      }

      String bindingName = bindingNameNode.getString();
      if (Strings.isNullOrEmpty(bindingName)) {
        this.nodeTraversal.report(this.node, MESSAGE_INVALID_DEPENDECY_GET_CALL);
      }

      injectionTargetInfo.putGet(bindingName, this.node);
    }
  }

  private final class ProviderMarkerProcessor implements InjectionMarkerProcessor {
    private Node node;

    private NodeTraversal nodeTraversal;

    public ProviderMarkerProcessor(NodeTraversal t, Node n) {
      this.node = n;
      this.nodeTraversal = t;
    }

    public void processMarker() {
      Node targetClass = this.node.getFirstChild().getNext();
      if (targetClass == null) {
        this.nodeTraversal.report(this.node, MESSAGE_DEFINE_PROVIDER_FIRST_ARGUMENT_IS_NOT_VALID);
      }
      Node provider = targetClass.getNext();

      if (provider == null) {
        this.nodeTraversal.report(this.node, MESSAGE_DEFINE_PROVIDER_SECOND_ARGUMENT_IS_NOT_VALID);
      }

      String qualifiedName = targetClass.getQualifiedName();
      if (qualifiedName != null) {
        injectionTargetInfo.putProvider(qualifiedName, provider);
      }
      this.node.getParent().detachFromParent();
    }
  }

  private final class ExportedClassMarkerProcessor implements InjectionMarkerProcessor {
    private Node node;

    private NodeTraversal nodeTraversal;

    public ExportedClassMarkerProcessor(NodeTraversal t, Node n) {
      this.node = n;
      this.nodeTraversal = t;
    }

    public void processMarker() {
      Node stringNode = this.node.getFirstChild().getNext();
      if (stringNode != null) {
        String name = stringNode.getString();
        if (!Strings.isNullOrEmpty(name)) {
          injectionTargetInfo.setProvide(name);
        }
      }
    }
  }

  private final class SingletonMarkerProcessor implements InjectionMarkerProcessor {
    private Node node;

    private NodeTraversal nodeTraversal;

    public SingletonMarkerProcessor(NodeTraversal t, Node n) {
      this.node = n;
      this.nodeTraversal = t;
    }

    public void processMarker() {
      Node targetClass = this.node.getFirstChild().getNext();
      if (targetClass != null) {
        String qualifiedName = targetClass.getQualifiedName();
        if (qualifiedName != null) {
          injectionTargetInfo.putSingleton(qualifiedName, this.node);
        }
      }
    }
  }

  private final class PrototypeMarkerProcessor implements InjectionMarkerProcessor {
    private Node node;

    private NodeTraversal nodeTraversal;

    public PrototypeMarkerProcessor(NodeTraversal t, Node n) {
      this.node = n;
      this.nodeTraversal = t;
    }

    public void processMarker() {
      Node lvalue = this.node.getFirstChild();
      Node rvalue = lvalue.getNext();
      if ((lvalue.isGetProp() || lvalue.isGetElem())) {
        this.collectPrototype(lvalue, rvalue);
      }
    }

    private void collectPrototype(Node lvalue, Node rvalue) {
      String qualifiedName = lvalue.getQualifiedName();
      if (qualifiedName != null) {
        String[] nameArr = qualifiedName.split("\\.");
        if (nameArr.length > 1 && qualifiedName.indexOf("." + PROTOTYPE) > -1) {
          String className = qualifiedName.substring(0, qualifiedName.indexOf("." + PROTOTYPE));
          // foo.prototype.bar = function() {...
          if (rvalue.isFunction() && qualifiedName.matches(PROTOTYPE_REGEX)) {
            String methodName = nameArr[nameArr.length - 1];
            this.addPrototypeMember(className, methodName, rvalue);

          } else if (qualifiedName.endsWith("." + PROTOTYPE) && rvalue.isObjectLit()) {
            // foo.prototype = {...
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
        }
      }
    }

    private void addPrototypeMember(String className, String methodName, Node function) {
      PrototypeInfo prototypeInfo = new PrototypeInfo(methodName, function);
      Node paramList = function.getFirstChild().getNext();
      for (Node param : paramList.children()) {
        prototypeInfo.addParam(param.getString());
      }
      injectionTargetInfo.putPrototypeInfo(className, prototypeInfo);
    }
  }

  private final class InterceptorMarkerProcessor implements InjectionMarkerProcessor {
    private Node node;

    private NodeTraversal nodeTraversal;

    public InterceptorMarkerProcessor(NodeTraversal nodeTraversal, Node node) {
      this.node = node;
      this.nodeTraversal = nodeTraversal;
    }

    public void processMarker() {
      Node packageMatcherNode = this.node.getFirstChild().getNext();
      if (packageMatcherNode != null) {
        Node methodMatcherNode = packageMatcherNode.getNext();
        String packageMatcher = packageMatcherNode.getString();
        String methodMatcher = methodMatcherNode.getString();
        if (!Strings.isNullOrEmpty(packageMatcher) && !Strings.isNullOrEmpty(methodMatcher)) {
          Node interceptor = methodMatcherNode.getNext();
          if (interceptor != null && interceptor.isFunction()) {
            packageMatcher = packageMatcher.replaceAll("\\.", "\\\\.").replaceAll("\\*", ".*");
            methodMatcher = methodMatcher.replaceAll("\\*", ".*");

            String interceptorName = INTERCEPTOR_REGISTRY + "." + INTERCEPTOR_PREFIX
                + interceptorId++;

            InterceptorRewriter interceptorRewriter = new InterceptorRewriter(interceptor,
                interceptor.getFirstChild().getNext(), this.nodeTraversal);

            NodeTraversal.traverseRoots(compiler, Lists.newArrayList(interceptor),
                interceptorRewriter);

            interceptor.detachFromParent();
            Node paramList = interceptor.getFirstChild().getNext();
            paramList.detachChildren();
            for (Node param : interceptorRewriter.getParamList()) {
              paramList.addChildToBack(param);
            }

            Node tmp = this.node;
            while (tmp != null && !tmp.isExprResult()) {
              tmp = tmp.getParent();
            }

            Node assign = new Node(Token.EXPR_RESULT, new Node(Token.ASSIGN,
                createQualifiedNameNode(interceptorName), interceptor));

            InterceptorInfo interceptorInfo = new InterceptorInfo(interceptorName, assign,
                packageMatcher, methodMatcher);

            interceptorInfo.setMethodNameAccess(interceptorRewriter.getMethodNameAccessFlag());
            interceptorInfo.setClassNameAccess(interceptorRewriter.getClassNameAccessFlag());
            interceptorInfo.setProceedAccess(interceptorRewriter.getProceedAccessFlag());

            injectionTargetInfo.addInterceptorInfo(interceptorInfo);
            tmp.getParent().addChildAfter(assign, tmp);
            if (tmp != null) {
              tmp.detachFromParent();
            }
          }
        }
      }
    }

    private final class InterceptorRewriter extends AbstractPostOrderCallback {
      private static final String GET_ARGUMENTS = "getArguments";

      private static final String GET_THIS = "getThis";

      private static final String GET_METHOD_NAME = "getMethodName";

      private static final String GET_CLASS_NAME = "getClassName";

      private static final String PROCEED = "proceed";

      private Node interceptor;

      private List<Node> functionParamList = Lists.newArrayList(
          Node.newString(Token.NAME, THIS_REFERENCE),
          Node.newString(Token.NAME, ARGUMENTS_REFERENCE),
          Node.newString(Token.NAME, METHOD_NAME_REFERENCE),
          Node.newString(Token.NAME, CLASS_NAME_REFERENCE),
          Node.newString(Token.NAME, RESULT_REFERENCE), Node.newString(Token.NAME, CALL_REFERENCE));

      private String methodInvocation = "";

      private Node methodInvocationNode;

      private boolean hasMethodNameAccess = false;

      private boolean hasClassNameAccess = false;

      private boolean hasProceedAccess = false;

      public InterceptorRewriter(Node interceptor, Node args, NodeTraversal t) {
        this.interceptor = interceptor;
        if (args != null) {
          this.methodInvocationNode = args.getFirstChild();
          this.methodInvocation = args.getFirstChild().getString();
        }
        JSDocInfoBuilder jsdocInfoBuilder = new JSDocInfoBuilder(false);
        Node functionType = Node.newString(Token.NAME, "Function");
        Node stringType = Node.newString(Token.NAME, "string");
        jsdocInfoBuilder.recordParameter(THIS_REFERENCE,
            new JSTypeExpression(functionType, t.getSourceName()));
        jsdocInfoBuilder.recordParameter(ARGUMENTS_REFERENCE,
            new JSTypeExpression(functionType, t.getSourceName()));
        jsdocInfoBuilder.recordParameter(METHOD_NAME_REFERENCE,
            new JSTypeExpression(stringType, t.getSourceName()));
        jsdocInfoBuilder.recordParameter(CLASS_NAME_REFERENCE,
            new JSTypeExpression(stringType.cloneNode(), t.getSourceName()));
        jsdocInfoBuilder.build(this.interceptor);
      }

      public List<Node> getParamList() {
        return this.functionParamList;
      }

      public boolean getMethodNameAccessFlag() {
        return this.hasMethodNameAccess;
      }

      public boolean getClassNameAccessFlag() {
        return this.hasClassNameAccess;
      }

      public boolean getProceedAccessFlag() {
        return this.hasProceedAccess;
      }

      @Override
      public void visit(NodeTraversal t, Node n, Node parent) {
        if (n.isCall()) {
          if (n.getFirstChild().isGetProp()) {
            Node firstChild = n.getFirstChild();
            String qualifiedName = firstChild.getQualifiedName();
            String[] arr = qualifiedName.split("\\.");
            String caller = arr[0];
            if (caller.equals(this.methodInvocation) && this.matchMethodInvocation(t, caller)) {
              if (arr[1].equals(GET_THIS)) {
                n.getParent().replaceChild(n, Node.newString(Token.NAME, THIS_REFERENCE));
              } else if (arr[1].equals(GET_ARGUMENTS)) {
                n.getParent().replaceChild(n, Node.newString(Token.NAME, ARGUMENTS_REFERENCE));
              } else if (arr[1].equals(PROCEED)) {
                n.getParent().replaceChild(n, this.makeProceed());
              } else if (arr[1].equals(GET_METHOD_NAME)) {
                this.hasMethodNameAccess = true;
                n.getParent().replaceChild(n, Node.newString(Token.NAME, METHOD_NAME_REFERENCE));
              } else if (arr[1].equals(GET_CLASS_NAME)) {
                this.hasClassNameAccess = true;
                n.getParent().replaceChild(n, Node.newString(Token.NAME, CLASS_NAME_REFERENCE));
              }
            }
          }
        }
      }

      private Node makeProceed() {
        this.hasProceedAccess = true;
        Node callNode = new Node(Token.CALL, createQualifiedNameNode(CALL_REFERENCE + ".call"),
            Node.newString(Token.NAME, THIS_REFERENCE));
        return new Node(Token.HOOK, new Node(Token.EQ, createQualifiedNameNode("arguments.length"),
            Node.newNumber(6)), callNode, Node.newString(Token.NAME, RESULT_REFERENCE));
      }

      private boolean matchMethodInvocation(NodeTraversal t, String name) {
        if (t.hasScope()) {
          Scope scope = t.getScope();
          Var var = scope.getVar(name);
          if (var != null && this.methodInvocationNode != null) {
            if (var.getNameNode().equals(this.methodInvocationNode)) {
              return true;
            }
          }
        }
        return false;
      }
    }
  }

  private final class InjectionFinder extends AbstractPostOrderCallback {

    private InjectionMarkerProcessorFactory injectionMarkerProcessorFactory;

    public InjectionFinder() {
      this.injectionMarkerProcessorFactory = new InjectionMarkerProcessorFactory();
    }

    @Override
    public void visit(NodeTraversal t, Node n, Node parent) {
      InjectionMarkerProcessor injectionMarkerProcessor = this.injectionMarkerProcessorFactory
          .create(t, n);
      if (injectionMarkerProcessor != null) {
        injectionMarkerProcessor.processMarker();
      }
    }
  }

  private final class AliasDefinitionFinder extends AbstractPostOrderCallback {
    private Map<String, Map<String, Node>> lvalueMap = new HashMap<String, Map<String, Node>>();

    private Map<String, Node> scopedLvalueMap;

    @Override
    public void visit(NodeTraversal t, Node n, Node parent) {
      if (!lvalueMap.containsKey(t.getSourceName())) {
        scopedLvalueMap = new HashMap<String, Node>();
        lvalueMap.put(t.getSourceName(), scopedLvalueMap);
      }
      switch (n.getType()) {
      case Token.ASSIGN:
        this.checkAssignment(t, n);
        break;

      case Token.VAR:
        this.checkVar(t, n);
        break;

      case Token.FUNCTION:
        this.checkFunction(t, n);
        break;
      }
    }

    private void checkAssignment(NodeTraversal t, Node n) {
      Node child = n.getFirstChild();
      if (child.isGetProp()) {

        String qualifiedName = child.getQualifiedName();

        if (qualifiedName != null) {
          scopedLvalueMap.put(qualifiedName, child.getNext());
          if (injectionTargetInfo.hasProvider(qualifiedName)) {
            this.findDefinition(t, child.getNext(), qualifiedName, true);
            ClassInfo info = classInfoMap.get(qualifiedName);
            if (info != null) {
              info.setProvider(injectionTargetInfo.getProvider(qualifiedName));
            }
          } else if (injectionTargetInfo.hasCreateInstanceTarget(qualifiedName)) {
            this.findDefinition(t, child.getNext(), qualifiedName, true);
          } else if (injectionTargetInfo.getProvide(qualifiedName)) {
            this.findDefinition(t, child.getNext(), qualifiedName, false);
          }
        }
      }
    }

    private void checkVar(NodeTraversal t, Node n) {
      Node nameNode = n.getFirstChild();
      Node initialValue = nameNode.getFirstChild();
      if (initialValue != null) {
        String name = nameNode.getString();
        if (injectionTargetInfo.hasProvider(name)) {
          this.findDefinition(t, initialValue, name, true);
          ClassInfo info = classInfoMap.get(name);
          if (info != null) {
            info.setProvider(injectionTargetInfo.getProvider(name));
          }
        } else if (injectionTargetInfo.hasCreateInstanceTarget(name)) {
          this.findDefinition(t, initialValue, name, true);
        } else if (injectionTargetInfo.getProvide(name)) {
          this.findDefinition(t, initialValue, name, false);
        }
      }
    }

    private void checkFunction(NodeTraversal t, Node n) {
      boolean isDeclaration = n.getParent().isExprResult();

      if (isDeclaration) {
        String name = n.getFirstChild().getString();
        if (!Strings.isNullOrEmpty(name)) {
          if (injectionTargetInfo.hasProvider(name)) {
            this.findDefinition(t, n, name, true);
            ClassInfo info = classInfoMap.get(name);
            if (info != null) {
              info.setProvider(injectionTargetInfo.getProvider(name));
            }
          } else if (injectionTargetInfo.hasCreateInstanceTarget(name)) {
            this.findDefinition(t, n, name, true);
          } else if (injectionTargetInfo.getProvide(name)) {
            this.findDefinition(t, n, name, false);
          }
        }
      }
    }

    private void findDefinition(NodeTraversal t, Node n, String name, boolean isCreateInstance) {
      this.doFindDefinition(t, n, name, isCreateInstance, new ClassInfo(name));
    }

    private void doFindDefinition(NodeTraversal t, Node n, String name, boolean isCreateInstance,
        ClassInfo classInfo) {
      switch (n.getType()) {
      case Token.NAME:
        String str = n.getString();
        if (str != null) {
          Var var = t.getScope().getVar(str);
          if (var != null) {
            Node value = var.getInitialValue();
            if (value != null) {
              this.setInfoToClassInfo(str, classInfo);
              this.doFindDefinition(t, value, name, isCreateInstance, classInfo);
            }
          }
        }
        break;

      case Token.GETELEM:
      case Token.GETPROP:
        String qualifiedName = n.getQualifiedName();
        if (qualifiedName != null) {
          if (scopedLvalueMap.containsKey(qualifiedName)) {
            this.setInfoToClassInfo(qualifiedName, classInfo);
            this.doFindDefinition(t, scopedLvalueMap.get(qualifiedName), name, isCreateInstance,
                classInfo);
          } else if (injectionTargetInfo.getProvide(qualifiedName)
              && classInfoMap.containsKey(qualifiedName)) {
            this.setInfoToClassInfo(name, classInfo);
          }
        }
        break;

      case Token.FUNCTION:
        JSDocInfo jsDocInfo = this.getJSDocInfoFromAncestorOr(n);

        if (jsDocInfo != null) {
          if (!jsDocInfo.isConstructor()) {
            if (isCreateInstance) {
              t.report(n, MESSAGE_CREATE_INSTANCE_TARGET_NOT_VALID);
            } else {
              return;
            }
          }
        } else {
          if (isCreateInstance) {
            t.report(n, MESSAGE_CREATE_INSTANCE_TARGET_NOT_VALID);
          } else {
            return;
          }
        }

        Node paramList = n.getFirstChild().getNext();
        for (Node child : paramList.children()) {
          classInfo.addParam(child.getString());
        }
        classInfoMap.put(name, classInfo);
        this.setInfoToClassInfo(name, classInfo);
        break;

      default:
        if (isCreateInstance) {
          t.report(n, MESSAGE_CREATE_INSTANCE_TARGET_NOT_VALID);
        }
      }
    }

    private void setInfoToClassInfo(String name, ClassInfo classInfo) {
      if (injectionTargetInfo.hasProvider(name) && classInfo.getProvider() == null) {
        classInfo.setProvider(injectionTargetInfo.getProvider(name));
      }

      if (injectionTargetInfo.isSingleton(name) && !classInfo.isSingleton()) {
        classInfo.setSingleton(true);
        classInfo.setSingletonCallNode(injectionTargetInfo.getSingletonNode(name));
      }

      if (injectionTargetInfo.hasPrototypeInfo(name)) {
        classInfo.setPrototypeInfoMap(injectionTargetInfo.getPrototypeInfo(name));
      }

      if (injectionTargetInfo.hasSetter(name)) {
        classInfo.setSetterList(injectionTargetInfo.getSetterMap(name));
      }
    }

    private JSDocInfo getJSDocInfoFromAncestorOr(Node n) {
      JSDocInfo info = n.getJSDocInfo();
      if (info == null) {
        return getJSDocInfoFromAncestorOr(n.getParent());
      }
      return info;
    }
  }

  private final class InterceptorWeaver {
    private void process(Node root) {

      this.addRegistryDeclaration(root);

      List<InterceptorInfo> interceptorInfoList = injectionTargetInfo.getInterceptorInfoList();

      for (String className : classInfoMap.keySet()) {
        for (InterceptorInfo interceptorInfo : interceptorInfoList) {

          String packageMatcher = interceptorInfo.getPackageMatcher();

          if (className.matches(packageMatcher)) {

            ClassInfo classInfo = classInfoMap.get(className);
            Map<String, PrototypeInfo> prototypeInfoMap = classInfo.getPrototypeInfoMap();

            if (prototypeInfoMap != null) {
              String methodMatcher = interceptorInfo.getMethodMatcher();

              for (String methodName : prototypeInfoMap.keySet()) {
                if (methodName.matches(methodMatcher)) {
                  PrototypeInfo prototypeInfo = prototypeInfoMap.get(methodName);
                  this.makeWeavingCall(prototypeInfo, interceptorInfo, className, methodName);
                }
              }
            }
          }
        }
      }

      this.clean();
    }

    private void addRegistryDeclaration(Node root) {
      Node registryNameNode = Node.newString(Token.NAME, INTERCEPTOR_REGISTRY);
      registryNameNode.addChildToBack(new Node(Token.OBJECTLIT));
      root.getFirstChild().addChildToBack(new Node(Token.VAR, registryNameNode));
    }

    private void makeWeavingCall(PrototypeInfo prototypeInfo, InterceptorInfo interceptorInfo,
        String className, String methodName) {

      Node weavingFunction = new Node(Token.CALL,
          createQualifiedNameNode(interceptorInfo.getName()), new Node(Token.THIS), Node.newString(
              Token.NAME, "arguments"));

      Node functionDefinition = prototypeInfo.getFunction();
      Node baseFunction = prototypeInfo.getBaseFunction();
      Node block = functionDefinition.getLastChild();
      Node result = new Node(Token.VAR, Node.newString(Token.NAME, INTERCEPTOR_RESULT_REFERENCE));

      if (!prototypeInfo.isWeaved()) {
        block.detachChildren();
        block.addChildToBack(result);
      }

      Node expr = new Node(Token.HOOK, weavingFunction.getFirstChild()
          .cloneTree(), weavingFunction, result.getFirstChild().cloneNode());
      if (block.getChildCount() > 1) {
        Node target = block.getChildAtIndex(block.getChildCount() - 1);
        block.replaceChild(target,
            new Node(Token.EXPR_RESULT, 
                new Node(Token.AND, target.getFirstChild().getFirstChild().cloneTree(), target.getFirstChild().getFirstChild().getNext().cloneTree())));
      }

      block.addChildToBack(new Node(Token.RETURN, expr));

      if (interceptorInfo.isMethodNameAccess()) {
        weavingFunction.addChildToBack(Node.newString(methodName));
      } else {
        weavingFunction.addChildToBack(Node.newString(""));
      }

      if (interceptorInfo.isClassNameAccess()) {
        weavingFunction.addChildToBack(Node.newString(className));
      } else {
        weavingFunction.addChildToBack(Node.newString(""));
      }

      if (interceptorInfo.isProceedAccess() && !prototypeInfo.isProceeded()) {
        prototypeInfo.setProceeded(true);
        weavingFunction.addChildToBack(new Node(Token.NULL));
        Node clone = baseFunction.cloneTree();
        clone.getFirstChild().getNext().detachChildren();
        weavingFunction.addChildToBack(clone);
        NodeTraversal.traverse(compiler, clone.getLastChild(),
            new ResultRewriter(clone.getLastChild(), result.getFirstChild()));
      } else if (interceptorInfo.isProceedAccess()) {
        weavingFunction.addChildToBack(result.getFirstChild().cloneNode());
      }

      prototypeInfo.setWeaved(true);
      interceptorInfo.setWeaved(true);
    }

    private void clean() {
      List<InterceptorInfo> interceptorInfoList = injectionTargetInfo.getInterceptorInfoList();
      for (InterceptorInfo interceptorInfo : interceptorInfoList) {
        Node interceptorAssignment = interceptorInfo.getInterceptor();
        if (!interceptorInfo.isWeaved() && interceptorAssignment.getParent() != null) {
          interceptorAssignment.detachFromParent();
        }
      }
    }

    private final class ResultRewriter extends AbstractPostOrderCallback {

      private Node scopeRoot;

      private Node resultReference;

      public ResultRewriter(Node scopeRoot, Node resultReference) {
        this.scopeRoot = scopeRoot;
        this.resultReference = resultReference;
      }

      public void visit(NodeTraversal t, Node n, Node parent) {
        if (n.isReturn()) {
          Scope scope = t.getScope();
          System.out.println(scope.getRootNode().equals(this.scopeRoot));
          if (scope.getRootNode().equals(this.scopeRoot)) {
            Node firstChild = n.getFirstChild();
            if (firstChild != null) {
              n.replaceChild(firstChild, new Node(Token.ASSIGN, this.resultReference.cloneNode(),
                  firstChild.cloneTree()));
            } else {
              n.addChildToBack(new Node(Token.ASSIGN, this.resultReference.cloneNode(), new Node(
                  Token.CALL, new Node(Token.VOID), Node.newNumber(0))));
            }
          }
        }
      }
    }
  }

  private final class InjectionRewriter {
    public void process() {
      this.inliningGetCall();
      this.inliningCreateInstanceCall();
      this.cleanBinding();
    }

    private void inliningCreateInstanceCall() {
      for (String key : injectionTargetInfo.getCreateInstanceTargetMapIter()) {
        Node createInstanceCall = injectionTargetInfo.getCreateInstanceTarget(key);
        ClassInfo info = classInfoMap.get(key);
        if (info != null) {
          Node child;
          if (info.getProvider() != null) {
            child = this.makeNewCallFromProvider(info);
          } else {
            child = this.makeNewCall(info);
          }

          child.copyInformationFromForTree(createInstanceCall);
          if (child != null) {
            createInstanceCall.getParent().replaceChild(createInstanceCall, child);
          }
        }
      }
    }

    private void inliningGetCall() {
      for (String key : injectionTargetInfo.getGetMapIter()) {
        Node node = injectionTargetInfo.getGetMap(key);
        Node newNode = this.resolveBinding(key);
        if (newNode != null) {
          node.getParent().replaceChild(node, newNode);
        } else {
          node.detachFromParent();
        }
      }
    }

    private void cleanBinding() {
      for (String key : injectionTargetInfo.getBindTargetIter()) {
        Pair<Node, Node> nodePair = injectionTargetInfo.getBindTarget(key);
        Node call = nodePair.getSecond();
        if (call.getParent() != null) {
          if (call.getParent().getParent() != null) {
            call.getParent().detachFromParent();
          }
        }
      }
    }

    private Node resolveBinding(String bindingName) {
      if (injectionTargetInfo.hasBindTarget(bindingName)) {
        Pair<Node, Node> binding = injectionTargetInfo.getBindTarget(bindingName);
        Node entity = binding.getFirst();
        Node call = binding.getSecond();
        ClassInfo info = null;
        String name;
        Node ret = null;
        if (entity.isGetProp() && (name = entity.getQualifiedName()) != null) {
          info = classInfoMap.get(name);
        } else if (entity.isName() && (name = entity.getString()) != null) {
          info = classInfoMap.get(name);
        }

        if (info != null) {
          if (info.getProvider() != null) {
            ret = this.makeNewCallFromProvider(info);
          } else {
            ret = this.makeNewCall(info);
          }
          if (call.getParent() != null) {
            if (call.getParent().getParent() != null) {
              call.getParent().detachFromParent();
            }
          }
        } else {
          Node newNode = new Node(Token.ASSIGN,
              createQualifiedNameNode("camp.dependencies.injectionRegistry." + bindingName),
              entity.cloneTree());
          if (call.getParent() != null) {
            call.getParent().replaceChild(call, newNode);
          }
          ret = createQualifiedNameNode("camp.dependencies.injectionRegistry." + bindingName);
        }
        return ret;
      }
      return new Node(Token.NULL);
    }

    private Node makeNewCallFromProvider(ClassInfo info) {
      Node function = info.getProvider();
      Node paramList = function.getFirstChild().getNext();
      Node ret = new Node(Token.CALL, function.cloneTree());
      for (Node param : paramList.children()) {
        ret.addChildToBack(this.resolveBinding(param.getString()));
      }
      return ret;
    }

    private Node makeNewCall(ClassInfo classInfo) {
      Node newCall;
      if (classInfo.isSingleton()) {
        newCall = this.makeSingleton(classInfo);
      } else {
        newCall = this.makeSimpleNewCall(classInfo);
        if (classInfo.getSetterList() != null) {
          return this.makeNewCallScope(newCall, classInfo);
        }
      }
      return newCall;
    }

    private Node makeSimpleNewCall(ClassInfo classInfo) {
      Node newCall = new Node(Token.NEW, createQualifiedNameNode(classInfo.getClassName()));
      for (String param : classInfo.getParamList()) {
        newCall.addChildToBack(this.resolveBinding(param));
      }
      return newCall;
    }

    private Node makeSingleton(ClassInfo classInfo) {
      Node singletonCall = classInfo.getSingletonCallNode();
      if (singletonCall != null) {
        Node getInstanceMirror = Node.newString(GET_INJECTIED_INSTANCE);
        Node className = singletonCall.getFirstChild().getNext().cloneTree();
        Node instanceHolder = new Node(Token.GETPROP, className.cloneTree(),
            Node.newString(INJECTED_INSTANCE));
        Node instaniationBlock = new Node(Token.BLOCK, new Node(Token.EXPR_RESULT, new Node(
            Token.ASSIGN, instanceHolder.cloneTree(), this.makeSimpleNewCall(classInfo))));

        if (classInfo.getSetterList() != null) {
          this.makeNewCallScopeBody(instaniationBlock, instanceHolder, classInfo);
        }

        Node expr = new Node(Token.EXPR_RESULT, new Node(Token.ASSIGN, new Node(Token.GETPROP,
            className, getInstanceMirror), new Node(Token.FUNCTION, Node.newString(Token.NAME, ""),
            new Node(Token.PARAM_LIST), new Node(Token.BLOCK, new Node(Token.IF, new Node(
                Token.NOT, instanceHolder.cloneTree()), instaniationBlock), new Node(Token.RETURN,
                instanceHolder.cloneTree())))));
        while (!singletonCall.isExprResult()) {
          singletonCall = singletonCall.getParent();
        }

        Node tmp = singletonCall;
        while (!tmp.isExprResult()) {
          tmp = tmp.getParent();
        }

        tmp.getParent().addChildAfter(expr, tmp);
        classInfo.setSingletonCallNode(null);
      }
      return new Node(Token.CALL, createQualifiedNameNode(classInfo.getClassName() + "."
          + GET_INJECTIED_INSTANCE));
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
              createQualifiedNameNode(instanceVar.getQualifiedName() + "." + setterName));
          for (String param : prototypeInfo.getParamList()) {
            setterCall.addChildToBack(this.resolveBinding(param));
          }
          block.addChildToBack(new Node(Token.EXPR_RESULT, setterCall));
        }
      }
    }
  }

  private static Node createQualifiedNameNode(String name) {
    String[] moduleNames = name.split("\\.");
    Node prop = null;
    for (String moduleName : moduleNames) {
      if (prop == null) {
        prop = Node.newString(Token.NAME, moduleName);
      } else {
        prop = new Node(Token.GETPROP, prop, Node.newString(moduleName));
      }
    }
    return prop;
  }

  private AbstractCompiler compiler;

  public CampInjectionProcessor(AbstractCompiler compiler) {
    this.compiler = compiler;
  }

  public void process(Node externs, Node root) {
    NodeTraversal.traverse(compiler, root, new InjectionFinder());
    NodeTraversal.traverse(compiler, root, new AliasDefinitionFinder());
    new InjectionRewriter().process();
    new InterceptorWeaver().process(root);
    compiler.reportCodeChange();
  }

}
