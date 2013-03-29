package com.google.javascript.jscomp;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.javascript.jscomp.NodeTraversal.AbstractPostOrderCallback;
import com.google.javascript.jscomp.Scope.Var;
import com.google.javascript.rhino.JSDocInfo;
import com.google.javascript.rhino.JSDocInfoBuilder;
import com.google.javascript.rhino.JSTypeExpression;
import com.google.javascript.rhino.Node;
import com.google.javascript.rhino.Token;

public class CampInjectionProcessor {

  private static final String JOIN_POINT_GET_CLASS_NAME = "getClassName";

  private static final String JOIN_POINT_GET_METHOD_NAME = "getMethodName";

  private static final String JOIN_POINT_GET_QUALIFIED_NAME = "getQualifiedName";

  private static final String JOIN_POINT_GET_ARGUMENTS = "getArguments";

  private static final String JOIN_POINT_GET_RESULT = "getResult";

  private static final String JOIN_POINT_GET_THIS = "getThis";

  private static final String MODULE_INIT_CALL = "camp.dependencies.modules.init";

  private static final String MODULE_INTERFACE = "camp.dependencies.Module";

  private static final String INJECT_CALL = "camp.dependencies.Injector.inject";

  private static final String CLASS_MATCHER_IN_NAMESPACE = "camp.dependencies.Matcher.inNamespace";

  private static final String CLASS_MATCHER_SUBCLASS_OF = "camp.dependencies.Matcher.subclassOf";

  private static final String CLASS_MATCHER_INSTANCE_OF = "camp.dependencies.Matcher.instanceOf";

  private static final String METHOD_MATCHER_LIKE = "camp.dependencies.Matcher.like";

  private static final String POINT_CUTS_AFTER = "camp.dependencies.Matcher.PointCuts.AFTER";

  private static final String POINT_CUTS_BEFORE = "camp.dependencies.Matcher.PointCuts.BEFORE";

  private static final String INTERCEPTOR_REGISTRY = "jscomp$interceptor$registry";

  private static final String INTERCEPTOR_RESULT = "jscomp$interceptor$result";

  private static final String INTERCEPTOR_NAME = "jscomp$interceptor$";

  private static final String GET_INJECTIED_INSTANCE = "jscomp$getInjectedInstance$";

  private static final String INJECTED_INSTANCE = "_jscomp$injectedInstance";

  private static final String SINGLETON_CALL = "goog.addSingletonGetter";

  private static final String MODULE_SETUP_METHOD_NAME = "configure";

  private static final String PROTOTYPE = "prototype";

  private static final String BIND = "bind";

  private static final String BIND_PROVIDER = "bindProvider";

  private static final String BIND_INTERCEPTOR = "bindInterceptor";

  private static final String CREATE_INSTANCE = "createInstance";

  private static final String THIS = "this";

  private static final String PROTOTYPE_REGEX = "(.*\\.prototype\\..*|.*\\.prototype$)";

  private int singletonId = 0;

  private int interceptorId = 0;

  private InjectionTargetInfo injectionTargetInfo = new InjectionTargetInfo();

  private final class InjectionTargetInfo {
    private Map<String, Map<String, PrototypeInfo>> prototypeInfoMap = Maps.newHashMap();

    private Map<String, ModuleInfo> moduleInfoMap = Maps.newHashMap();

    private List<ModuleInitInfo> moduleInitInfoList = Lists.newArrayList();

    private Map<String, ClassInfo> classInfoMap = Maps.newHashMap();

    private Map<String, Node> singletonMap = Maps.newHashMap();

    private Map<String, Map<String, BindingInfo>> bindingInfoMap = Maps.newHashMap();

    private Map<String, List<InterceptorInfo>> interceptorInfoMap = Maps.newHashMap();

    private Map<String, List<String>> setterMap = Maps.newHashMap();


    /**
     * @return the prototypeInfoMap
     */
    public Map<String, PrototypeInfo> getPrototypeInfo(String className) {
      return prototypeInfoMap.get(className);
    }


    /**
     * @param prototypeInfoMap
     *          the prototypeInfoMap to set
     */
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


    /**
     * @return the moduleInfoMap
     */
    public ModuleInfo getModuleInfo(String name) {
      return this.moduleInfoMap.get(name);
    }


    public Map<String, ModuleInfo> getModuleInfoMap() {
      return this.moduleInfoMap;
    }


    public boolean hasModuleInfo(String name) {
      return this.moduleInfoMap.containsKey(name);
    }


    /**
     * @param moduleInfoMap
     *          the moduleInfoMap to set
     */
    public void putModuleInfo(ModuleInfo moduleInfo) {
      this.moduleInfoMap.put(moduleInfo.getModuleName(), moduleInfo);
    }


    /**
     * @param prototypeInfoMap
     *          the prototypeInfoMap to set
     */
    public void setPrototypeInfoMap(Map<String, Map<String, PrototypeInfo>> prototypeInfoMap) {
      this.prototypeInfoMap = prototypeInfoMap;
    }


    public void addModuleInitInfo(ModuleInitInfo moduleInitInfo) {
      this.moduleInitInfoList.add(moduleInitInfo);
    }


    public List<ModuleInitInfo> getModuleInitInfoList() {
      return this.moduleInitInfoList;
    }


    public void putClassInfo(ClassInfo classInfo) {
      this.classInfoMap.put(classInfo.getClassName(), classInfo);
    }


    public ClassInfo getClassInfo(String className) {
      return this.classInfoMap.get(className);
    }


    public Map<String, ClassInfo> getClassInfoMap() {
      return this.classInfoMap;
    }


    public void putSingleton(String className, Node node) {
      this.singletonMap.put(className, node);
    }


    public Node getSingleton(String className) {
      return this.singletonMap.get(className);
    }


    public void putBindingInfo(String className, BindingInfo bindingInfo) {
      Map<String, BindingInfo> map;
      if (!this.bindingInfoMap.containsKey(className)) {
        map = Maps.newHashMap();
        this.bindingInfoMap.put(className, map);
      } else {
        map = this.bindingInfoMap.get(className);
      }
      map.put(bindingInfo.getName(), bindingInfo);
    }


    public Map<String, BindingInfo> getBindingInfoMap(String className) {
      return this.bindingInfoMap.get(className);
    }


    private void putInterceptorInfo(String className, InterceptorInfo interceptorInfo) {
      List<InterceptorInfo> list = null;
      if (!this.interceptorInfoMap.containsKey(className)) {
        list = Lists.newArrayList();
        this.interceptorInfoMap.put(className, list);
      } else {
        list = this.interceptorInfoMap.get(className);
      }
      list.add(interceptorInfo);
    }


    private List<InterceptorInfo> getInterceptorInfo(String className) {
      return this.interceptorInfoMap.get(className);
    }


    public void putSetter(String className, String setterName) {
      if (!this.setterMap.containsKey(className)) {
        this.setterMap.put(className, new ArrayList<String>());
      }
      List<String> setterList = this.setterMap.get(className);
      setterList.add(setterName);
    }


    public List<String> getSetterList(String name) {
      return this.setterMap.get(name);
    }


    public boolean hasSetter(String name) {
      return this.setterMap.containsKey(name);
    }
  }

  private enum ClassMatchType {
    IN_NAMESPACE, SUBCLASS_OF, INSTANCE_OF
  }

  private enum MethodMatchType {
    LIKE
  }

  private enum JoinPointType {
    AFTER, BEFORE
  }

  private final class InterceptorInfo {
    private String classMatcher;

    private String methodMatcher;

    private JoinPointType joinPoint;

    private String name;

    private ClassMatchType classMatchType;

    private MethodMatchType methodMatchType;

    private Node interceptor;

    private boolean classNameAccess = false;

    private boolean methodNameAccess = false;


    /**
     * @return the classMatcher
     */
    public String getClassMatcher() {
      return classMatcher;
    }


    /**
     * @param classMatcher
     *          the classMatcher to set
     */
    public void setClassMatcher(String classMatcher) {
      this.classMatcher = classMatcher;
    }


    /**
     * @return the methodMatcher
     */
    public String getMethodMatcher() {
      return methodMatcher;
    }


    /**
     * @param methodMatcher
     *          the methodMatcher to set
     */
    public void setMethodMatcher(String methodMatcher) {
      this.methodMatcher = methodMatcher;
    }


    /**
     * @return the joinPoint
     */
    public JoinPointType getJoinPoint() {
      return joinPoint;
    }


    /**
     * @param joinPoint
     *          the joinPoint to set
     */
    public void setJoinPoint(JoinPointType joinPoint) {
      this.joinPoint = joinPoint;
    }


    /**
     * @return the name
     */
    public String getName() {
      return name;
    }


    /**
     * @param name
     *          the name to set
     */
    public void setName(String name) {
      this.name = name;
    }


    /**
     * @return the classMatchType
     */
    public ClassMatchType getMatchType() {
      return classMatchType;
    }


    /**
     * @param classMatchType
     *          the classMatchType to set
     */
    public void setMatchType(ClassMatchType classMatchType) {
      this.classMatchType = classMatchType;
    }


    /**
     * @return the classMatchType
     */
    public ClassMatchType getClassMatchType() {
      return classMatchType;
    }


    /**
     * @param classMatchType
     *          the classMatchType to set
     */
    public void setClassMatchType(ClassMatchType classMatchType) {
      this.classMatchType = classMatchType;
    }


    /**
     * @return the methodMatchType
     */
    public MethodMatchType getMethodMatchType() {
      return methodMatchType;
    }


    /**
     * @param methodMatchType
     *          the methodMatchType to set
     */
    public void setMethodMatchType(MethodMatchType methodMatchType) {
      this.methodMatchType = methodMatchType;
    }


    /**
     * @return the interceptor
     */
    public Node getInterceptor() {
      return interceptor;
    }


    /**
     * @param interceptor
     *          the interceptor to set
     */
    public void setInterceptor(Node interceptor) {
      this.interceptor = interceptor;
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
  }

  private final class ClassInfo {
    private String className;

    private List<String> paramList = Lists.newArrayList();

    private List<String> setterList = Lists.newArrayList();

    private Node provider;

    private Node singletonCallNode;

    private Node function;

    private Map<String, PrototypeInfo> prototypeInfoMap = Maps.newHashMap();

    private List<BindingInfo> bindingInfoList = Lists.newArrayList();

    private Map<String, Node> injectedSingletonCallMap = Maps.newHashMap();

    private Map<String, Boolean> interceptorMap = Maps.newLinkedHashMap();

    private JSDocInfo jsDocInfo;


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
    public Node getSingletonCallNode() {
      return singletonCallNode;
    }


    public boolean isSingleton() {
      return this.singletonCallNode != null;
    }


    /**
     * @param isSingleton
     *          the isSingleton to set
     */
    public void setSingletonCallNode(Node singletonCallNode) {
      this.singletonCallNode = singletonCallNode;
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
      this.setterList.addAll(setterList);
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
     * @return the function
     */
    public Node getFunction() {
      return function;
    }


    /**
     * @param function
     *          the function to set
     */
    public void setFunction(Node function) {
      this.function = function;
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
      this.prototypeInfoMap.putAll(prototypeInfoMap);
    }


    public void addBindingInfo(BindingInfo bindingInfo) {
      this.bindingInfoList.add(bindingInfo);
    }


    public List<BindingInfo> getBindingInfoList() {
      return this.bindingInfoList;
    }


    /**
     * @return the jsDocInfo
     */
    public JSDocInfo getJSDocInfo() {
      return jsDocInfo;
    }


    /**
     * @param jsDocInfo
     *          the jsDocInfo to set
     */
    public void setJSDocInfo(JSDocInfo jsDocInfo) {
      this.jsDocInfo = jsDocInfo;
    }


    public void putInjectedSingletonCall(String name, Node injectedSingletonCall) {
      this.injectedSingletonCallMap.put(name, injectedSingletonCall);
    }


    public Map<String, Node> getInjectedSingletonCallMap() {
      return this.injectedSingletonCallMap;
    }


    public boolean hasInterceptor(String name) {
      return this.interceptorMap.containsKey(name);
    }


    public void putInterceptor(String name) {
      this.interceptorMap.put(name, true);
    }
  }

  private final class ModuleInitInfo {
    private List<String> configModuleList;

    private Node moduleInitCall;


    /**
     * @return the configModuleList
     */
    public List<String> getConfigModuleList() {
      return configModuleList;
    }


    /**
     * @param configModuleList
     *          the configModuleList to set
     */
    public void setConfigModuleList(List<String> configModuleList) {
      this.configModuleList = configModuleList;
    }


    /**
     * @return the moduleInitCall
     */
    public Node getModuleInitCall() {
      return moduleInitCall;
    }


    /**
     * @param moduleInitCall
     *          the moduleInitCall to set
     */
    public void setModuleInitCall(Node moduleInitCall) {
      this.moduleInitCall = moduleInitCall;
    }

  }

  private final class ModuleInfo {
    private String moduleName;

    private Map<String, BindingInfo> bindingInfoMap = Maps.newHashMap();

    private Node moduleMethodNode;

    private boolean interceptorRewrited;


    /**
     * @return the moduleName
     */
    public String getModuleName() {
      return moduleName;
    }


    /**
     * @param moduleName
     *          the moduleName to set
     */
    public void setModuleName(String moduleName) {
      this.moduleName = moduleName;
    }


    /**
     * @return the bindingInfoMap
     */
    public Map<String, BindingInfo> getBindingInfoMap() {
      return bindingInfoMap;
    }


    /**
     * @param bindingInfoMap
     *          the bindingInfoMap to set
     */
    public void setBindingInfoMap(Map<String, BindingInfo> bindingInfoMap) {
      this.bindingInfoMap = bindingInfoMap;
    }


    /**
     * @return the moduleMethodNode
     */
    public Node getModuleMethodNode() {
      return moduleMethodNode;
    }


    /**
     * @param moduleMethodNode
     *          the moduleMethodNode to set
     */
    public void setModuleMethodNode(Node moduleMethodNode) {
      this.moduleMethodNode = moduleMethodNode;
    }


    /**
     * @return the interceptorRewrited
     */
    public boolean isInterceptorRewrited() {
      return interceptorRewrited;
    }


    /**
     * @param interceptorRewrited
     *          the interceptorRewrited to set
     */
    public void setInterceptorRewrited(boolean interceptorRewrited) {
      this.interceptorRewrited = interceptorRewrited;
    }
  }

  private final class BindingInfo {
    private Node bindingCall;

    private String name;

    private Node expression;

    private boolean isProvider;

    private Node provider;


    /**
     * @return the bindingCall
     */
    public Node getBindingCall() {
      return bindingCall;
    }


    /**
     * @param bindingCall
     *          the bindingCall to set
     */
    public void setBindingCall(Node bindingCall) {
      this.bindingCall = bindingCall;
    }


    /**
     * @return the name
     */
    public String getName() {
      return name;
    }


    /**
     * @param name
     *          the name to set
     */
    public void setName(String name) {
      this.name = name;
    }


    /**
     * @return the expression
     */
    public Node getExpression() {
      return expression;
    }


    /**
     * @param expression
     *          the expression to set
     */
    public void setExpression(Node expression) {
      this.expression = expression;
    }


    /**
     * @return the isProvider
     */
    public boolean hasProvider() {
      return isProvider;
    }


    /**
     * @param isProvider
     *          the isProvider to set
     */
    public void setProviderFlag(boolean isProvider) {
      this.isProvider = isProvider;
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

  }

  private final class PrototypeInfo {
    private Node function;

    private Node lastInsertedInterceptor;

    private List<String> paramList = Lists.newArrayList();

    private String name;

    private boolean weaved = false;

    private Map<String, Boolean> interceptorMap = Maps.newHashMap();


    public PrototypeInfo(String name, Node function) {
      this.function = function;
      this.name = name;
    }


    public void addParam(String name) {
      this.paramList.add(name);
    }


    /**
     * @return the lastInsertedInterceptor
     */
    public Node getLastInsertedInterceptor() {
      return lastInsertedInterceptor;
    }


    /**
     * @param lastInsertedInterceptor
     *          the lastInsertedInterceptor to set
     */
    public void setLastInsertedInterceptor(Node lastInsertedInterceptor) {
      this.lastInsertedInterceptor = lastInsertedInterceptor;
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


    public boolean isWeaved() {
      return weaved;
    }


    public void setWeaved(boolean weaved) {
      this.weaved = weaved;
    }


    /**
     * @return the interceptorMap
     */
    public boolean hasInterceptor(String name) {
      return interceptorMap.containsKey(name);
    }


    /**
     * @param interceptorMap
     *          the interceptorMap to set
     */
    public void putInterceptor(String name) {
      this.interceptorMap.put(name, true);
    }
  }

  private interface MarkerProcessor {
    public void processMarker();
  }

  private final class MarkerProcessorFactory {
    public MarkerProcessor create(NodeTraversal t, Node n) {
      if (n.isAssign()) {
        if (n.getFirstChild().isGetProp()) {
          String qualifiedName = n.getFirstChild().getQualifiedName();
          if (qualifiedName != null) {
            if (qualifiedName.indexOf("." + PROTOTYPE) > -1) {
              return new PrototypeMarkerProcessor(t, n);
            }
          }
        }
      } else if (n.isFunction()) {
        JSDocInfo info = NodeUtil.getBestJSDocInfo(n);
        if (info != null && info.isConstructor()) {
          return new ModuleOrClassMarkerProcessor(t, n);
        }
      } else if (n.isCall()) {
        if (n.getFirstChild().isGetProp()) {
          String qualifiedName = n.getFirstChild().getQualifiedName();
          if (qualifiedName != null) {
            if (qualifiedName.equals(MODULE_INIT_CALL)) {
              return new ModuleInitMarkerProcessor(t, n);
            } else if (qualifiedName.equals(SINGLETON_CALL)) {
              return new SingletonMarkerProcessor(t, n);
            } else if (qualifiedName.equals(INJECT_CALL)) {
              return new InjectMarkerProcessor(t, n);
            }
          }
        }
      }
      return null;
    }
  }

  private final class ModuleOrClassMarkerProcessor implements MarkerProcessor {
    private Node node;

    private NodeTraversal nodeTraversal;


    public ModuleOrClassMarkerProcessor(NodeTraversal t, Node n) {
      this.node = n;
      this.nodeTraversal = t;
    }


    public void processMarker() {
      JSDocInfo info = NodeUtil.getBestJSDocInfo(this.node);
      if (info != null && info.isConstructor()) {
        if (info.getImplementedInterfaceCount() > 0) {
          List<JSTypeExpression> typeList = info.getImplementedInterfaces();
          for (JSTypeExpression jsType : typeList) {
            Node typeNode = jsType.getRoot();
            if (typeNode != null && typeNode.getType() == Token.BANG) {
              typeNode = typeNode.getFirstChild();
              if (typeNode.isString() && typeNode.getString() != null
                  && typeNode.getString().equals(MODULE_INTERFACE)) {
                this.caseModule(info);
                return;
              }
            }
          }
        }
      }
      this.caseClass();
    }


    private void caseModule(JSDocInfo info) {
      Node parent = this.node.getParent();
      ModuleInfo moduleInfo = null;
      if (parent.isAssign()) {
        moduleInfo = new ModuleInfo();
        moduleInfo.setModuleName(parent.getFirstChild().getQualifiedName());
      } else if (NodeUtil.isFunctionDeclaration(this.node)) {
        moduleInfo = new ModuleInfo();
        moduleInfo.setModuleName(this.node.getFirstChild().getString());
      } else if (NodeUtil.isVarDeclaration(parent)) {
        moduleInfo = new ModuleInfo();
        moduleInfo.setModuleName(parent.getString());
      }
      if (moduleInfo != null) {
        injectionTargetInfo.putModuleInfo(moduleInfo);
      }
    }


    private void caseClass() {
      ClassInfo classInfo = null;
      if (NodeUtil.isFunctionDeclaration(this.node)) {
        String name = this.node.getFirstChild().getString();
        classInfo = new ClassInfo(name);
      } else {
        Node parent = this.node.getParent();
        if (parent.isAssign()) {
          String name = parent.getFirstChild().getQualifiedName();
          classInfo = new ClassInfo(name);
        } else if (NodeUtil.isVarDeclaration(parent)) {
          classInfo = new ClassInfo(parent.getString());
        }
      }

      if (classInfo != null) {
        Node paramList = this.node.getFirstChild().getNext();
        classInfo.setJSDocInfo(NodeUtil.getBestJSDocInfo(this.node));
        classInfo.setFunction(this.node);
        for (Node param : paramList.children()) {
          classInfo.addParam(param.getString());
        }
        injectionTargetInfo.putClassInfo(classInfo);
      }
    }
  }

  private final class ModuleInitMarkerProcessor implements MarkerProcessor {
    private Node node;

    private NodeTraversal nodeTraversal;


    public ModuleInitMarkerProcessor(NodeTraversal t, Node n) {
      this.node = n;
      this.nodeTraversal = t;
    }


    public void processMarker() {
      Node maybeConfig = this.node.getFirstChild().getNext();
      if (maybeConfig != null) {
        List<String> moduleList = Lists.newArrayList();
        if (maybeConfig.isArrayLit()) {
          for (Node module : maybeConfig.children()) {
            if ((module.isGetProp() || module.isName()) && module.getQualifiedName() != null) {
              moduleList.add(module.getQualifiedName());
            }
          }
          Node closure = maybeConfig.getNext();
          if (closure != null && closure.isFunction()) {
            ModuleInitInfo moduleInitInfo = new ModuleInitInfo();
            moduleInitInfo.setConfigModuleList(moduleList);
            moduleInitInfo.setModuleInitCall(closure);
            injectionTargetInfo.addModuleInitInfo(moduleInitInfo);
          }
        }
      }
    }
  }

  private final class InjectMarkerProcessor implements MarkerProcessor {
    private Node node;

    private NodeTraversal nodeTraversal;


    public InjectMarkerProcessor(NodeTraversal t, Node n) {
      this.node = n;
      this.nodeTraversal = t;
    }


    public void processMarker() {
      Node classNameNode = this.node.getFirstChild().getNext();
      String setterName = this.getSetterName(classNameNode.getNext());
      String className = this.getClassName(classNameNode);
      if (className != null && setterName != null) {
        injectionTargetInfo.putSetter(className, setterName);
        Node setterNameNode = classNameNode.getNext();
        while (setterNameNode != null) {
          setterNameNode = setterNameNode.getNext();
          setterName = this.getSetterName(setterNameNode);
          if (setterName != null) {
            injectionTargetInfo.putSetter(className, setterName);
          } else {
            break;
          }
        }
        detachStatement(this.node);
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

  private final class SingletonMarkerProcessor implements MarkerProcessor {
    private Node node;

    private NodeTraversal nodeTraversal;


    public SingletonMarkerProcessor(NodeTraversal t, Node n) {
      this.node = n;
      this.nodeTraversal = t;
    }


    public void processMarker() {
      Node arg = this.node.getFirstChild().getNext();
      if (arg != null) {
        String qualifiedName = arg.getQualifiedName();
        if (qualifiedName != null) {
          injectionTargetInfo.putSingleton(qualifiedName, this.node);
        }
      }
    }
  }

  private final class PrototypeMarkerProcessor implements MarkerProcessor {
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

  private interface NodeRewriteProcessor {
    public void rewrite(NodeTraversal t, Node n, Node firstArguments);
  }

  private final class Rewriter {

    public void rewrite() {
      this.bindClassInfo();
      this.bindModuleInfo();
      this.rewriteMarkers();
      this.bindBindingInfo();
      this.rewriteInjections();
    }


    private void bindClassInfo() {
      Map<String, ClassInfo> classInfoMap = injectionTargetInfo.getClassInfoMap();
      for (String className : classInfoMap.keySet()) {
        ClassInfo classInfo = classInfoMap.get(className);
        Map<String, PrototypeInfo> prototypeInfoMap = injectionTargetInfo
            .getPrototypeInfo(className);
        if (prototypeInfoMap != null) {
          classInfo.setPrototypeInfoMap(prototypeInfoMap);
        }

        if (injectionTargetInfo.hasSetter(className)) {
          classInfo.setSetterList(injectionTargetInfo.getSetterList(className));
        }

        classInfo.setSingletonCallNode(injectionTargetInfo.getSingleton(className));
      }
    }


    private void bindModuleInfo() {
      Map<String, ModuleInfo> moduleInfoMap = injectionTargetInfo.getModuleInfoMap();
      for (String className : moduleInfoMap.keySet()) {
        ModuleInfo moduleInfo = moduleInfoMap.get(className);
        Map<String, BindingInfo> bindingInfoMap = injectionTargetInfo.getBindingInfoMap(className);
        Map<String, PrototypeInfo> prototypeInfoMap = injectionTargetInfo
            .getPrototypeInfo(className);
        if (prototypeInfoMap != null) {
          for (String methodName : prototypeInfoMap.keySet()) {
            PrototypeInfo prototypeInfo = prototypeInfoMap.get(methodName);
            if (methodName.equals(MODULE_SETUP_METHOD_NAME)) {
              Node function = prototypeInfo.getFunction();
              function.setJSDocInfo(null);
              moduleInfo.setModuleMethodNode(function);
            }
          }
        }

        if (bindingInfoMap != null) {
          moduleInfo.setBindingInfoMap(bindingInfoMap);
        }
      }
    }


    private void bindBindingInfo() {
      Map<String, ModuleInfo> moduleInfoMap = injectionTargetInfo.getModuleInfoMap();
      for (String className : moduleInfoMap.keySet()) {
        ModuleInfo moduleInfo = moduleInfoMap.get(className);
        Map<String, BindingInfo> bindingInfoMap = injectionTargetInfo.getBindingInfoMap(className);
        if (bindingInfoMap != null) {
          moduleInfo.setBindingInfoMap(bindingInfoMap);
        }
      }
    }


    private void rewriteMarkers() {
      Map<String, ModuleInfo> moduleInfoMap = injectionTargetInfo.getModuleInfoMap();
      for (ModuleInfo moduleInfo : moduleInfoMap.values()) {
        Node function = moduleInfo.getModuleMethodNode();
        Node paramList = function.getFirstChild().getNext();
        Node binder = paramList.getFirstChild();
        if (binder != null) {
          NodeTraversal.traverseRoots(
              compiler,
              Lists.newArrayList(function),
              new RewriteCallback(
                  binder, new BindCallRewriteProcessor(moduleInfo.getModuleName(), moduleInfo
                      .isInterceptorRewrited())));
          moduleInfo.getModuleMethodNode().getFirstChild().getNext().detachChildren();
          moduleInfo.setInterceptorRewrited(true);
        }
      }
    }


    private void rewriteInjections() {
      for (ModuleInitInfo moduleInitInfo : injectionTargetInfo.getModuleInitInfoList()) {
        Node function = moduleInitInfo.getModuleInitCall();
        Node moduleInitCall = function.getParent();
        Node injector = function.getFirstChild().getNext().getFirstChild();
        if (injector != null) {
          List<String> moduleConfigList = moduleInitInfo.getConfigModuleList();
          NodeTraversal.traverseRoots(compiler, Lists.newArrayList(function), new RewriteCallback(
              injector, new ModuleInitRewriteProcessor(moduleConfigList)));
          injector.detachFromParent();
          this.rewriteCall(moduleConfigList, moduleInitCall, function);
        }
      }
    }


    private void rewriteCall(List<String> moduleConfigList, Node moduleInitCall, Node function) {
      function.detachFromParent();
      Node block = function.getLastChild();
      moduleInitCall.getParent().replaceChild(moduleInitCall, new Node(Token.CALL, function));
      List<String> copied = Lists.newArrayList();
      copied.addAll(moduleConfigList);
      Collections.reverse(copied);
      for (String config : copied) {
        Node varName = Node.newString(Token.NAME, toLowerCase(getValidVarName(config)));
        Node callSetup = new Node(Token.NEW, createQualifiedNameNode(config));
        varName.addChildToBack(callSetup);
        Node var = new Node(Token.VAR, varName);
        block.addChildBefore(var, block.getFirstChild());
        Node callConfigure = new Node(Token.EXPR_RESULT, new Node(Token.CALL,
            createQualifiedNameNode(varName.getString() + "." + MODULE_SETUP_METHOD_NAME)));
        block.addChildAfter(callConfigure, var);
      }
    }

    private final class ModuleInitRewriteProcessor implements NodeRewriteProcessor {
      private Map<String, Map<String, BindingInfo>> allBindingInfoMap = Maps.newHashMap();

      private Map<String, List<InterceptorInfo>> interceptorMap = Maps.newHashMap();

      private Map<ClassInfo, String> singletonMap = Maps.newHashMap();


      public ModuleInitRewriteProcessor(List<String> moduleConfigList) {
        singletonId++;
        for (String moduleName : moduleConfigList) {
          ModuleInfo moduleInfo = injectionTargetInfo.getModuleInfo(moduleName);
          if (moduleInfo != null) {
            this.allBindingInfoMap.put(moduleInfo.getModuleName(), moduleInfo.getBindingInfoMap());
            List<InterceptorInfo> infoList = injectionTargetInfo.getInterceptorInfo(moduleName);
            if (infoList != null) {
              List<InterceptorInfo> list = Lists.newArrayList();
              this.interceptorMap.put(toLowerCase(getValidVarName(moduleName)), list);
              for (InterceptorInfo interceptorInfo : infoList) {
                list.add(interceptorInfo);
              }
            }
          }
        }
        this.weavingInterceptor();
      }


      @Override
      public void rewrite(NodeTraversal t, Node n, Node firstChild) {
        String name = n.getQualifiedName();
        String injector = firstChild.getString();
        if (name.equals(injector + "." + CREATE_INSTANCE)) {
          Node classNode = n.getNext();
          if (classNode != null) {
            String className = classNode.getQualifiedName();
            if (className != null) {
              this.inliningCreateInstanceCall(t, n, className);
            }
          }
        }
      }


      private void inliningCreateInstanceCall(NodeTraversal t, Node n, String className) {
        Node createInstanceCall = n.getParent();
        ClassInfo info = injectionTargetInfo.getClassInfo(className);
        if (info != null) {
          Node child = null;
          if (info.getProvider() != null) {
            String varName = toLowerCase(getValidVarName(info.getClassName()));
            for (String cName : this.allBindingInfoMap.keySet()) {
              Map<String, BindingInfo> bindingMap = this.allBindingInfoMap.get(cName);
              if (bindingMap.containsKey(varName)) {
                String targetClassVar = toLowerCase(getValidVarName(cName));
                child = this.makeNewCallFromProvider(info.getProvider(), new Node(Token.CALL,
                    createQualifiedNameNode(targetClassVar + "." + varName)));
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
          String lowerClassName = toLowerCase(getValidVarName(className));
          Map<String, BindingInfo> bindingMap = this.allBindingInfoMap.get(className);
          if (bindingMap.containsKey(bindingName)) {
            BindingInfo bindingInfo = bindingMap.get(bindingName);
            Node entity = bindingInfo.getExpression();
            ClassInfo info = null;
            String name;
            Node ret = null;

            if (bindingInfo.getProvider() == null) {
              Preconditions.checkState(entity != null, "In module " + className + " binding "
                  + bindingName + " is not found.");
              if (entity.isGetProp() && (name = entity.getQualifiedName()) != null) {
                info = injectionTargetInfo.getClassInfo(name);
              } else if (entity.isName() && (name = entity.getString()) != null) {
                info = injectionTargetInfo.getClassInfo(name);
              }
            }

            if (info != null) {
              if (info.getProvider() != null) {
                String varName = toLowerCase(getValidVarName(info.getClassName()));
                ret = this.makeNewCallFromProvider(info.getProvider(), new Node(Token.CALL,
                    createQualifiedNameNode(lowerClassName + "." + varName)));
              } else {
                ret = this.makeNewCall(info);
              }
            } else {
              Node provider = bindingInfo.getProvider();
              if (provider != null) {
                ret = this.makeNewCallFromProvider(provider, new Node(Token.CALL,
                    createQualifiedNameNode(lowerClassName + "."
                        + bindingInfo.getName())));
              } else {
                ret = createQualifiedNameNode(lowerClassName + "."
                    + bindingName);
              }
            }
            return ret;
          } else {
            List<InterceptorInfo> interceptorInfoList = injectionTargetInfo
                .getInterceptorInfo(className);
            if (interceptorInfoList != null) {
              for (InterceptorInfo interceptorInfo : interceptorInfoList) {
                String name = interceptorInfo.getName();
                if (!Strings.isNullOrEmpty(name) && name.equals(bindingName)) {
                  return createQualifiedNameNode(lowerClassName + "."
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
          List<InterceptorInfo> infoList = injectionTargetInfo.getInterceptorInfo(moduleName);
          if (infoList != null) {
            for (InterceptorInfo interceptorInfo : infoList) {
              Map<String, ClassInfo> classInfoMap = injectionTargetInfo.getClassInfoMap();
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
        Node var = new Node(Token.VAR, Node.newString(Token.NAME, INTERCEPTOR_RESULT));
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
              newChild.getFirstChild().addChildToBack(makeUndefined());
            }
            n.getParent().replaceChild(n, newChild);
          }
        }
      }


      private Node makeInterceptorCall(InterceptorInfo interceptorInfo, String className,
          String methodName, boolean isAfter) {
        Node nameNode = createQualifiedNameNode(THIS + "." + interceptorInfo.getName());
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
          call.addChildToBack(Node.newString(Token.NAME, INTERCEPTOR_RESULT));
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
        Node block = classInfo.getFunction().getLastChild();
        Node paramListNode = classInfo.getFunction().getFirstChild().getNext();
        String name = interceptorInfo.getName();

        if (paramList.indexOf(name) == -1) {
          Node nameNode = Node.newString(Token.NAME, name);
          paramList.add(name);
          paramListNode.addChildToBack(nameNode);
          block.addChildToFront(new Node(Token.EXPR_RESULT, new Node(Token.ASSIGN,
              createQualifiedNameNode(THIS + "."
                  + interceptorInfo.getName()), nameNode.cloneNode())));
        }
      }


      private Node makeSimpleNewCall(ClassInfo classInfo) {
        Node newCall = new Node(Token.NEW, createQualifiedNameNode(classInfo.getClassName()));
        for (String param : classInfo.getParamList()) {
          newCall.addChildToBack(this.resolveBinding(param));
        }
        return newCall;
      }


      private Node makeSingleton(ClassInfo classInfo, Map<String, InterceptorInfo> matchedMap) {
        if (!this.singletonMap.containsKey(classInfo)) {
          Node singletonCall = classInfo.getSingletonCallNode();
          String name = GET_INJECTIED_INSTANCE + singletonId;
          Node getInstanceMirror = Node.newString(name);
          Node className = singletonCall.getFirstChild().getNext().cloneTree();
          Node instanceHolder = new Node(Token.GETPROP, className.cloneTree(),
              Node.newString(INJECTED_INSTANCE));
          Node newCall = this.makeSimpleNewCall(classInfo);

          Node instaniationBlock = new Node(Token.BLOCK, new Node(Token.EXPR_RESULT, new Node(
              Token.ASSIGN, instanceHolder.cloneTree(), newCall)));

          if (classInfo.getSetterList() != null) {
            this.makeNewCallScopeBody(instaniationBlock, instanceHolder, classInfo);
          }

          Node paramList = new Node(Token.PARAM_LIST);

          for (String moduleName : this.allBindingInfoMap.keySet()) {
            paramList.addChildToBack(Node.newString(Token.NAME,
                toLowerCase(getValidVarName(moduleName))));
          }

          Node expr = new Node(Token.EXPR_RESULT, new Node(Token.ASSIGN, new Node(Token.GETPROP,
              className, getInstanceMirror), new Node(Token.FUNCTION,
              Node.newString(Token.NAME, ""), paramList, new Node(Token.BLOCK,
                  new Node(Token.IF, new Node(Token.NOT, instanceHolder.cloneTree()),
                      instaniationBlock), new Node(Token.RETURN, instanceHolder.cloneTree())))));

          Node function = expr.getFirstChild().getLastChild();

          Node tmp = getStatementParent(singletonCall);
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

        Node call = new Node(Token.CALL, createQualifiedNameNode(classInfo.getClassName() + "."
            + this.singletonMap.get(classInfo)));
        for (String moduleName : this.allBindingInfoMap.keySet()) {
          call.addChildToBack(Node.newString(Token.NAME, toLowerCase(getValidVarName(moduleName))));
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
                createQualifiedNameNode(instanceVar.getQualifiedName() + "." + setterName));
            for (String param : prototypeInfo.getParamList()) {
              setterCall.addChildToBack(this.resolveBinding(param));
            }
            block.addChildToBack(new Node(Token.EXPR_RESULT, setterCall));
          }
        }
      }
    }

    private final class BindCallRewriteProcessor implements NodeRewriteProcessor {
      String className;


      public BindCallRewriteProcessor(String className, boolean interceptorRewrited) {
        this.className = className;
      }


      public void rewrite(NodeTraversal t, Node n, Node firstChild) {
        String qualifiedName = n.getQualifiedName();
        String binderName = firstChild.getString();
        Scope scope = t.getScope();
        Var var = scope.getVar(n.getFirstChild().getString());
        if (var != null && var.getNameNode().equals(firstChild)) {
          if (qualifiedName != null) {
            if (qualifiedName.equals(binderName + "." + BIND)) {
              this.caseBind(t, n);
            } else if (qualifiedName.equals(binderName + "." + BIND_PROVIDER)) {
              this.caseProvider(t, n);
            } else if (qualifiedName.equals(binderName + "." + BIND_INTERCEPTOR)) {
              this.caseInterceptor(t, n);
            }
          }
        }
      }


      private void caseBind(NodeTraversal t, Node n) {
        BindingInfo bindingInfo = new BindingInfo();
        Node bindNameNode = n.getNext();
        if (bindNameNode.isString()) {
          bindingInfo.setName(bindNameNode.getString());
          Node expressionNode = bindNameNode.getNext();
          if (expressionNode != null) {
            bindingInfo.setExpression(expressionNode);
            injectionTargetInfo.putBindingInfo(this.className, bindingInfo);
            this.rewriteBinding(t, n, bindNameNode.getString(), expressionNode);
          }
        }
      }


      private void caseProvider(NodeTraversal t, Node n) {
        Node bindNameNode = n.getNext();
        BindingInfo bindingInfo = null;
        String bindName = null;
        if (bindNameNode.isString()) {
          bindName = bindNameNode.getString();
          bindingInfo = new BindingInfo();
          bindingInfo.setProviderFlag(true);
          bindingInfo.setName(bindNameNode.getString());
        }
        Node classNode = bindNameNode.getNext();
        if (classNode.isName() || classNode.isGetProp()) {
          String name = classNode.getQualifiedName();
          if (!Strings.isNullOrEmpty(name)) {
            ClassInfo classInfo = injectionTargetInfo.getClassInfo(name);
            if (classInfo != null) {
              Node provider = classNode.getNext();
              if (provider != null) {
                classInfo.setProvider(provider);
                if (bindingInfo != null) {
                  bindingInfo.setProvider(provider);
                  injectionTargetInfo.putBindingInfo(this.className, bindingInfo);
                }
                bindingInfo = new BindingInfo();
                bindingInfo.setProviderFlag(true);
                bindingInfo.setName(toLowerCase(getValidVarName(classInfo.getClassName())));
                bindingInfo.setProvider(provider);
                injectionTargetInfo.putBindingInfo(this.className, bindingInfo);
                this.rewriteProvider(provider, n, classInfo.getClassName(), bindName);
              }
            }
          }
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
                InterceptorInfo interceptorInfo = this.getInterceptorInfo(
                    classMatcher.getFirstChild(),
                    methodMatcher.getFirstChild(), pointCuts, interceptor);
                this.rewriteInterceptor(t, n, interceptorInfo);
              }
            }
          }
        }
      }


      private InterceptorInfo getInterceptorInfo(Node classMatcher, Node methodMatcher,
          Node pointCuts, Node interceptor) {
        InterceptorInfo interceptorInfo = new InterceptorInfo();
        String classMatchTypeCallName = classMatcher.getQualifiedName();
        this.setClassMatchType(classMatchTypeCallName, classMatcher, interceptorInfo);
        String methodMatchTypeCallName = methodMatcher.getQualifiedName();
        this.setMethodMatchType(methodMatchTypeCallName, methodMatcher, interceptorInfo);
        String pointCutsCall = pointCuts.getQualifiedName();
        this.setJoinPoint(pointCutsCall, pointCuts, interceptorInfo);
        interceptorInfo.setInterceptor(interceptor);
        injectionTargetInfo.putInterceptorInfo(this.className, interceptorInfo);
        return interceptorInfo;
      }


      private void setClassMatchType(String matchTypeCallName, Node classMatcher,
          InterceptorInfo interceptorInfo) {
        Node node = classMatcher.getNext();
        if (node != null) {
          if (matchTypeCallName.equals(CLASS_MATCHER_IN_NAMESPACE)) {
            if (node.isString()) {
              String name = node.getString();
              if (!Strings.isNullOrEmpty(name)) {
                interceptorInfo.setMatchType(ClassMatchType.IN_NAMESPACE);
                interceptorInfo.setClassMatcher(name);
              }
            }
          } else if (matchTypeCallName.equals(CLASS_MATCHER_SUBCLASS_OF)) {
            if (node.isGetProp()) {
              String name = node.getQualifiedName();
              if (name != null) {
                interceptorInfo.setMatchType(ClassMatchType.SUBCLASS_OF);
                interceptorInfo.setClassMatcher(name);
              }
            }
          } else if (matchTypeCallName.equals(CLASS_MATCHER_INSTANCE_OF)) {
            if (node.isGetProp()) {
              String name = node.getQualifiedName();
              if (name != null) {
                interceptorInfo.setMatchType(ClassMatchType.INSTANCE_OF);
                interceptorInfo.setClassMatcher(name);
              }
            }
          }
        }
      }


      private void setMethodMatchType(String matchTypeCallName, Node classMatcher,
          InterceptorInfo interceptorInfo) {
        Node node = classMatcher.getNext();
        if (node != null) {
          if (matchTypeCallName.equals(METHOD_MATCHER_LIKE)) {
            if (node.isString()) {
              String name = node.getString();
              if (!Strings.isNullOrEmpty(name)) {
                interceptorInfo.setMethodMatchType(MethodMatchType.LIKE);
                interceptorInfo.setMethodMatcher(name);
              }
            }
          }
        }
      }


      private void setJoinPoint(String pointCuts, Node pointCutsNode,
          InterceptorInfo interceptorInfo) {
        if (pointCuts.equals(POINT_CUTS_AFTER)) {
          interceptorInfo.setJoinPoint(JoinPointType.AFTER);
        } else if (pointCuts.equals(POINT_CUTS_BEFORE)) {
          interceptorInfo.setJoinPoint(JoinPointType.BEFORE);
        }
      }


      private void rewriteBinding(NodeTraversal t, Node n, String bindingName, Node expression) {

        Node tmp = getStatementParent(n);
        Preconditions.checkNotNull(tmp);

        if (expression.isName() || expression.isGetProp()) {
          String name = expression.getQualifiedName();
          if (name != null && injectionTargetInfo.getClassInfo(name) != null) {
            tmp.detachFromParent();
            return;
          }
        }

        tmp.getParent().addChildAfter(
            new Node(Token.EXPR_RESULT, new Node(Token.ASSIGN, createQualifiedNameNode(THIS + "."
                + bindingName), expression.cloneTree())), tmp);
        tmp.detachFromParent();
      }


      private void rewriteProvider(Node function, Node n, String className, String bindingName) {
        n = getStatementParent(n);
        if (bindingName != null) {
          n.getParent().addChildBefore(
              new Node(Token.EXPR_RESULT,
                  new Node(Token.ASSIGN,
                      createQualifiedNameNode(THIS + "." + bindingName),
                      function.cloneTree())), n);
        }

        Node provider = bindingName != null ? createQualifiedNameNode(THIS + "." + bindingName)
            : function.cloneTree();
        n.getParent().addChildAfter(
            new Node(Token.EXPR_RESULT,
                new Node(Token.ASSIGN, createQualifiedNameNode(THIS + "."
                    + toLowerCase(getValidVarName(className))),
                    provider)), n);
        n.detachFromParent();
      }


      private void rewriteInterceptor(NodeTraversal t, Node n, InterceptorInfo interceptorInfo) {
        Node function = interceptorInfo.getInterceptor();
        Node tmp = getStatementParent(n);
        Preconditions.checkNotNull(tmp);
        String interceptorName = THIS + "." + INTERCEPTOR_NAME
            + interceptorId;

        Node paramList = function.getFirstChild().getNext();
        Node joinPoint = paramList.getFirstChild();
        if (joinPoint != null) {
          JoinPointRewriter joinPointRewriter = new JoinPointRewriter(joinPoint, t);
          NodeTraversal.traverseRoots(compiler, Lists.newArrayList(function), joinPointRewriter);
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
                createQualifiedNameNode(interceptorName), function)), tmp);
        interceptorInfo.setName(INTERCEPTOR_NAME + interceptorId);
        interceptorId++;
        tmp.detachFromParent();
      }
    }

    private final class JoinPointRewriter extends AbstractPostOrderCallback {

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


      public JoinPointRewriter(Node joinPoint, NodeTraversal t) {
        this.joinPoint = joinPoint;
        JSDocInfoBuilder jsdocInfoBuilder = new JSDocInfoBuilder(false);
        Node anyType = Node.newString(Token.NAME, "*");
        Node stringType = Node.newString(Token.NAME, "string");
        Node argumentsType = Node.newString(Token.NAME, "arguments");

        jsdocInfoBuilder.recordParameter(CONTEXT,
            new JSTypeExpression(anyType, t.getSourceName()));
        jsdocInfoBuilder.recordParameter(ARGS,
            new JSTypeExpression(argumentsType, t.getSourceName()));
        jsdocInfoBuilder.recordParameter(CLASS_NAME,
            new JSTypeExpression(stringType, t.getSourceName()));
        jsdocInfoBuilder.recordParameter(METHOD_NAME, new JSTypeExpression(stringType.cloneNode(),
            t.getSourceName()));
        jsdocInfoBuilder.recordParameter(RESULT, JSTypeExpression
            .makeOptionalArg(new JSTypeExpression(stringType.cloneNode(), t.getSourceName())));

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


      @Override
      public void visit(NodeTraversal t, Node n, Node parent) {
        if (n.isCall()) {
          parent = n.getFirstChild();
          n = parent.getFirstChild();
          if (n.isName() && parent.isGetProp() && n.getString() != null) {
            Scope scope = t.getScope();
            if (scope != null) {
              Var var = scope.getVar(n.getString());
              if (var != null && var.getNameNode().equals(this.joinPoint)) {
                this.rewriteJoinPointMethod(n.getNext(), parent.getParent());
              }
            }
          }
        }
      }


      private void rewriteJoinPointMethod(Node methodNode, Node n) {
        String method = methodNode.getString();
        if (method != null) {
          if (method.equals(JOIN_POINT_GET_ARGUMENTS)) {
            n.getParent().replaceChild(n, Node.newString(Token.NAME, ARGS));
          } else if (method.equals(JOIN_POINT_GET_CLASS_NAME)) {
            this.classNameAccess = true;
            n.getParent().replaceChild(n, Node.newString(Token.NAME, CLASS_NAME));
          } else if (method.equals(JOIN_POINT_GET_METHOD_NAME)) {
            this.methodNameAccess = true;
            n.getParent().replaceChild(n, Node.newString(Token.NAME, METHOD_NAME));
          } else if (method.equals(JOIN_POINT_GET_QUALIFIED_NAME)) {
            this.methodNameAccess = true;
            this.classNameAccess = true;
            n.getParent().replaceChild(
                n,
                new Node(Token.ADD, Node.newString(Token.NAME, CLASS_NAME), new Node(Token.ADD,
                    Node.newString("."), Node.newString(Token.NAME, METHOD_NAME))));
          } else if (method.equals(JOIN_POINT_GET_THIS)) {
            n.getParent().replaceChild(n, Node.newString(Token.NAME, CONTEXT));
          } else if (method.equals(JOIN_POINT_GET_RESULT)) {
            n.getParent().replaceChild(n, Node.newString(Token.NAME, RESULT));
          }
        }
      }
    }

    private final class RewriteCallback extends AbstractPostOrderCallback {
      private Node firstArgument;

      private NodeRewriteProcessor processor;


      public RewriteCallback(Node firstArgument, NodeRewriteProcessor processor) {
        this.firstArgument = firstArgument;
        this.processor = processor;
      }


      @Override
      public void visit(NodeTraversal t, Node n, Node parent) {
        Scope scope = t.getScope();
        if (parent.isCall()) {
          if (n.isGetProp() && n.getFirstChild().isName()) {
            String name = n.getFirstChild().getString();
            Var var = scope.getVar(name);
            if (var != null) {
              Node nameNode = var.getNameNode();
              if (nameNode.equals(this.firstArgument)) {
                processor.rewrite(t, n, this.firstArgument);
              }
            }
          }
        }
      }
    }
  }

  private final class InformationCollector extends AbstractPostOrderCallback {
    private MarkerProcessorFactory factory = new MarkerProcessorFactory();


    public void visit(NodeTraversal t, Node n, Node parent) {
      MarkerProcessor markerProcessor = factory.create(t, n);
      if (markerProcessor != null) {
        markerProcessor.processMarker();
      }
    }
  }


  public void process(Node extern, Node root) {
    NodeTraversal.traverse(compiler, root, new InformationCollector());
    new Rewriter().rewrite();
  }


  public CampInjectionProcessor(AbstractCompiler compiler) {
    this.compiler = compiler;
  }

  private AbstractCompiler compiler;


  private static Node createQualifiedNameNode(String name) {
    String[] moduleNames = name.split("\\.");
    Node prop = null;
    for (String moduleName : moduleNames) {
      if (prop == null) {
        prop = makeNameNodeOrKeyWord(moduleName, true);
      } else {
        prop = new Node(Token.GETPROP, prop, makeNameNodeOrKeyWord(moduleName, false));
      }
    }
    return prop;
  }


  private static String toLowerCase(String className) {
    char a = className.charAt(0);
    return Character.toLowerCase(a) + className.substring(1);
  }


  private static String getValidVarName(String className) {
    return className.replaceAll("\\.", "_");
  }


  private static void detachStatement(Node n) {
    n = getStatementParent(n);
    if (n != null) {
      n.detachFromParent();
    }
  }


  private static Node getStatementParent(Node n) {
    while (n != null && !NodeUtil.isStatement(n)) {
      n = n.getParent();
    }
    return n;
  }


  private static Node makeUndefined() {
    return new Node(Token.CALL, new Node(Token.VOID), Node.newNumber(0));
  }


  private static Node makeNameNodeOrKeyWord(String name, boolean isFirst) {
    if (name.equals("this")) {
      return new Node(Token.THIS);
    } else {
      if (isFirst) {
        return Node.newString(Token.NAME, name);
      } else {
        return Node.newString(name);
      }
    }
  }
}
