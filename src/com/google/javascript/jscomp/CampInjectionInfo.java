package com.google.javascript.jscomp;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.javascript.jscomp.CampInjectionConsts.ClassMatchType;
import com.google.javascript.jscomp.CampInjectionConsts.JoinPointType;
import com.google.javascript.jscomp.CampInjectionConsts.MethodMatchType;
import com.google.javascript.rhino.JSDocInfo;
import com.google.javascript.rhino.Node;

final class CampInjectionInfo {
  private Map<String, Map<String, PrototypeInfo>> prototypeInfoMap = Maps.newHashMap();

  private Map<String, ModuleInfo> moduleInfoMap = Maps.newHashMap();

  private List<ModuleInitializerInfo> moduleInitInfoList = Lists.newArrayList();

  private Map<String, ClassInfo> classInfoMap = Maps.newHashMap();

  private Map<String, Node> singletonMap = Maps.newHashMap();

  private Map<String, Map<String, BindingInfo>> bindingInfoMap = Maps.newHashMap();

  private Map<String, List<InterceptorInfo>> interceptorInfoMap = Maps.newHashMap();

  private Map<String, List<String>> setterMap = Maps.newHashMap();

  private static CampInjectionInfo instance = null;


  private CampInjectionInfo() {
  }


  public static CampInjectionInfo getInstance() {
    if (instance == null) {
      synchronized (CampInjectionInfo.class) {
        if (instance == null) {
          instance = new CampInjectionInfo();
        }
      }
    }
    return instance;
  }


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


  /**
   * @param moduleInfoMap
   *          the moduleInfoMap to set
   */
  public void putModuleInfo(ModuleInfo moduleInfo) {
    this.moduleInfoMap.put(moduleInfo.getModuleName(), moduleInfo);
  }


  public void addModuleInitInfo(ModuleInitializerInfo moduleInitializerInfo) {
    this.moduleInitInfoList.add(moduleInitializerInfo);
  }


  public List<ModuleInitializerInfo> getModuleInitInfoList() {
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


  void putInterceptorInfo(String className, InterceptorInfo interceptorInfo) {
    List<InterceptorInfo> list = null;
    if (!this.interceptorInfoMap.containsKey(className)) {
      list = Lists.newArrayList();
      this.interceptorInfoMap.put(className, list);
    } else {
      list = this.interceptorInfoMap.get(className);
    }
    list.add(interceptorInfo);
  }


  List<InterceptorInfo> getInterceptorInfo(String className) {
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


  static final class InterceptorInfo {
    private String classMatcher;

    private String methodMatcher;

    private CampInjectionConsts.JoinPointType joinPoint;

    private String name;

    private ClassMatchType classMatchType;

    private MethodMatchType methodMatchType;

    private Node interceptorNode;

    private boolean classNameAccess = false;

    private boolean methodNameAccess = false;

    private String moduleName;


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
     * @param classMatchType
     *          the classMatchType to set
     */
    public void setClassMatchType(ClassMatchType classMatchType) {
      this.classMatchType = classMatchType;
    }


    /**
     * @return the classMatchType
     */
    public ClassMatchType getClassMatchType() {
      return classMatchType;
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
    public Node getInterceptorNode() {
      return interceptorNode;
    }


    /**
     * @param interceptorNode
     *          the interceptor to set
     */
    public void setInterceptorNode(Node interceptorNode) {
      this.interceptorNode = interceptorNode;
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


    public String getModuleName() {
      return moduleName;
    }


    public void setModuleName(String moduleName) {
      this.moduleName = moduleName;
    }
  }


  static final class ClassInfo {
    private String className;

    private List<String> paramList = Lists.newArrayList();

    private List<String> setterList = Lists.newArrayList();

    private boolean hasInterceptor = false;

    private Node providerNode;

    private Node singletonCallNode;

    private Node constructorNode;

    private Map<String, PrototypeInfo> prototypeInfoMap = Maps.newHashMap();

    private Map<String, Node> injectedSingletonCallMap = Maps.newHashMap();

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


    public void setInterceptorFlag() {
      this.hasInterceptor = true;
    }


    public boolean hasInterceptorFlag() {
      return this.hasInterceptor;
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
    public Node getProviderNode() {
      return providerNode;
    }


    /**
     * @param providerNode
     *          the provider to set
     */
    public void setProviderNode(Node providerNode) {
      this.providerNode = providerNode;
    }


    /**
     * @return the function
     */
    public Node getConstructorNode() {
      return constructorNode;
    }


    /**
     * @param constructorNode
     *          the function to set
     */
    public void setConstructorNode(Node constructorNode) {
      this.constructorNode = constructorNode;
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
  }


  static final class ModuleInitializerInfo {
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


  static final class ModuleInfo {
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


  static final class BindingInfo {
    private boolean asProvider;

    private String name;

    private Node bindedExpressionNode;

    private Node providerNode;

    private ClassInfo classInfo;


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
    public Node getBindedExpressionNode() {
      return bindedExpressionNode;
    }


    /**
     * @param bindedExpressionNode
     *          the expression to set
     */
    public void setBindedExpressionNode(Node bindedExpressionNode) {
      this.bindedExpressionNode = bindedExpressionNode;
    }


    /**
     * @param isProvider
     *          the isProvider to set
     */
    public void registeredAsProvider(boolean isProvider) {
      this.asProvider = asProvider;
    }


    public boolean isRegisteredAsProvider() {
      return this.asProvider;
    }


    /**
     * @return the provider
     */
    public Node getProviderNode() {
      return providerNode;
    }


    /**
     * @param provider
     *          the provider to set
     */
    public void setProvider(Node provider) {
      this.providerNode = provider;
    }


    public ClassInfo getClassInfo() {
      return classInfo;
    }


    public void setClassInfo(ClassInfo classInfo) {
      this.classInfo = classInfo;
    }

  }


  static final class PrototypeInfo implements Cloneable {
    private Node methodNode;

    private Node lastInsertedInterceptorNode;

    private List<String> paramList = Lists.newArrayList();

    private String name;

    private boolean weaved = false;

    private List<InterceptorInfo> interceptorInfoList = Lists.newArrayList();


    public PrototypeInfo(String name, Node function) {
      this.methodNode = function;
      this.name = name;
    }


    public void addParam(String name) {
      this.paramList.add(name);
    }


    /**
     * @return the lastInsertedInterceptor
     */
    public Node getLastInsertedInterceptor() {
      return lastInsertedInterceptorNode;
    }


    /**
     * @param lastInsertedInterceptor
     *          the lastInsertedInterceptor to set
     */
    public void setLastInsertedInterceptor(Node lastInsertedInterceptor) {
      this.lastInsertedInterceptorNode = lastInsertedInterceptor;
    }


    public void addInterceptor(InterceptorInfo interceptorInfo) {
      this.interceptorInfoList.add(interceptorInfo);
    }


    public List<InterceptorInfo> getInterceptorInfoList() {
      return this.interceptorInfoList;
    }


    public List<String> getParamList() {
      return this.paramList;
    }


    public String getMethodName() {
      return this.name;
    }


    public Node getFunction() {
      return this.methodNode;
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
    public List<InterceptorInfo> getInterceptor(String name) {
      return this.interceptorInfoList;
    }


    /**
     * @param interceptorMap
     *          the interceptorMap to set
     */
    public void addInterceptorInfo(InterceptorInfo interceptorInfo) {
      this.interceptorInfoList.add(interceptorInfo);
    }


    @Override
    public Object clone() {
      try {
        return super.clone();
      } catch (CloneNotSupportedException e) {
        throw new InternalError(e.toString());
      }
    }
  }
}