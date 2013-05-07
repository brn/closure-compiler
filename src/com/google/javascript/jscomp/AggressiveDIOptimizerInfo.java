package com.google.javascript.jscomp;

import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.common.base.Strings;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ImmutableBiMap;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.javascript.jscomp.DIConsts.ClassMatchType;
import com.google.javascript.jscomp.DIConsts.MethodMatchType;
import com.google.javascript.rhino.JSDocInfo;
import com.google.javascript.rhino.Node;

final class AggressiveDIOptimizerInfo {
  private Map<String, Map<String, PrototypeInfo>> prototypeInfoMap = Maps.newHashMap();

  private Map<String, ModuleInfo> moduleInfoMap = Maps.newHashMap();

  private List<ModuleInitializerInfo> moduleInitInfoList = Lists.newArrayList();

  private Map<String, ConstructorInfo> constructorInfoMap = Maps.newHashMap();

  private Map<String, ArrayListMultimap<String, BindingInfo>> bindingInfoMap = Maps.newHashMap();

  private ArrayListMultimap<String, InterceptorInfo> interceptorInfoMap = ArrayListMultimap
      .create();

  private ArrayListMultimap<String, MethodInjectionInfo> methodInjectionInfoMap = ArrayListMultimap
      .create();

  private Map<String, String> methodInjectionDeclarations = Maps.newHashMap();


  /**
   * @return the prototypeInfoMap
   */
  public Map<String, PrototypeInfo> getPrototypeInfo(String constructorName) {
    return prototypeInfoMap.get(constructorName);
  }


  /**
   * @param prototypeInfoMap
   *          the prototypeInfoMap to set
   */
  public void putPrototypeInfo(String constructorName, PrototypeInfo prototypeInfo) {
    Map<String, PrototypeInfo> prototypeInfoList;
    if (this.prototypeInfoMap.containsKey(constructorName)) {
      prototypeInfoList = prototypeInfoMap.get(constructorName);
    } else {
      prototypeInfoList = Maps.newHashMap();
      this.prototypeInfoMap.put(constructorName, prototypeInfoList);
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


  public void putConstructorInfo(ConstructorInfo constructorInfo) {
    this.constructorInfoMap.put(constructorInfo.getConstructorName(), constructorInfo);
  }


  public ConstructorInfo getConstructorInfo(String constructorName) {
    return this.constructorInfoMap.get(constructorName);
  }


  public Map<String, ConstructorInfo> getConstructorInfoMap() {
    return this.constructorInfoMap;
  }


  public boolean hasBindingInfo(String constructorName) {
    return bindingInfoMap.containsKey(constructorName);
  }


  public boolean hasBindingInfo(String constructorName, String bindingName) {
    ArrayListMultimap<String, BindingInfo> bindingMap = bindingInfoMap.get(constructorName);
    if (bindingMap != null && bindingMap.size() > 1) {
      return bindingMap.containsKey(bindingName);
    }
    return false;
  }


  public void putBindingInfo(String constructorName, BindingInfo bindingInfo) {
    ArrayListMultimap<String, BindingInfo> map;
    if (!this.bindingInfoMap.containsKey(constructorName)) {
      map = ArrayListMultimap.create();
      this.bindingInfoMap.put(constructorName, map);
    }
    map = this.bindingInfoMap.get(constructorName);
    map.put(bindingInfo.getName(), bindingInfo);
  }


  public ArrayListMultimap<String, BindingInfo> getBindingInfoMap(String constructorName) {
    return this.bindingInfoMap.get(constructorName);
  }


  void putInterceptorInfo(String constructorName, InterceptorInfo interceptorInfo) {
    this.interceptorInfoMap.put(constructorName, interceptorInfo);
  }


  List<InterceptorInfo> getInterceptorInfo(String constructorName) {
    return this.interceptorInfoMap.get(constructorName);
  }


  public void putMethodInjectionInfo(String constructorName, MethodInjectionInfo methodInjectionInfo) {
    this.methodInjectionInfoMap.put(constructorName, methodInjectionInfo);
    this.methodInjectionDeclarations.put(constructorName, methodInjectionInfo.getMethodName());
  }


  public boolean hasMethodInjectionInfo(String constructorName, String methodName) {
    String injectionTarget = this.methodInjectionDeclarations.get(constructorName);
    if (!Strings.isNullOrEmpty(injectionTarget)) {
      return injectionTarget.equals(methodName);
    }
    return false;
  }


  public List<MethodInjectionInfo> getMethodInjectionInfoList(String name) {
    return this.methodInjectionInfoMap.get(name);
  }


  public boolean hasSetter(String name) {
    return this.methodInjectionInfoMap.containsKey(name);
  }


  static final class InterceptorInfo {

    private String classMatcher;

    private String methodMatcher;

    private String name;

    private ClassMatchType classMatchType;

    private MethodMatchType methodMatchType;

    private Node interceptorCallNode;

    private Node interceptorNode;

    private boolean constructorNameAccess = false;

    private boolean methodNameAccess = false;

    private List<Node> proceedNodeList = Lists.newArrayList();

    private List<Node> argumentsNodeList = Lists.newArrayList();

    private List<Node> thisNodeList = Lists.newArrayList();

    private List<Node> constructorNameNodeList = Lists.newArrayList();

    private List<Node> methodNameNodeList = Lists.newArrayList();

    private List<Node> qualifiedNameNodeList = Lists.newArrayList();

    private String moduleName;

    private boolean isInConditional;


    /**
     * @return the classMatcher
     */
    public String getConstructorMatcher() {
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
    public ClassMatchType getConstructorMatchType() {
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
     * @return the interceptorCallNode
     */
    public Node getInterceptorCallNode() {
      return interceptorCallNode;
    }


    /**
     * @param interceptorCallNode
     *          the interceptorCallNode to set
     */
    public void setInterceptorCallNode(Node interceptorCallNode) {
      this.interceptorCallNode = interceptorCallNode;
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
     * @return the constructorNameAccess
     */
    public boolean isConstructorNameAccess() {
      return constructorNameAccess;
    }


    /**
     * @param constructorNameAccess
     *          the constructorNameAccess to set
     */
    public void recordConstructorNameAccess(boolean constructorNameAccess) {
      this.constructorNameAccess = constructorNameAccess;
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
    public void recordMethodNameAccess(boolean methodNameAccess) {
      this.methodNameAccess = methodNameAccess;
    }


    public String getModuleName() {
      return moduleName;
    }


    public void setModuleName(String moduleName) {
      this.moduleName = moduleName;
    }


    /**
     * @return the proceedNodeList
     */
    public List<Node> getProceedNodeList() {
      return proceedNodeList;
    }


    /**
     * @param proceedNode
     *          the proceedNodeList to set
     */
    public void addProceedNode(Node proceedNode) {
      this.proceedNodeList.add(proceedNode);
    }


    /**
     * @return the argumentsNodeList
     */
    public List<Node> getArgumentsNodeList() {
      return argumentsNodeList;
    }


    /**
     * @param argumentsNode
     *          the argumentsNodeList to set
     */
    public void addArgumentsNode(Node argumentsNode) {
      this.argumentsNodeList.add(argumentsNode);
    }


    /**
     * @return the thisNodeList
     */
    public List<Node> getThisNodeList() {
      return thisNodeList;
    }


    /**
     * @param thisNode
     *          the thisNodeList to set
     */
    public void addThisNode(Node thisNode) {
      this.thisNodeList.add(thisNode);
    }


    /**
     * @return the constructorNameNodeList
     */
    public List<Node> getConstructorNameNodeList() {
      return constructorNameNodeList;
    }


    /**
     * @param constructorNameNode
     *          the constructorNameNodeList to set
     */
    public void addConstructorNameNode(Node constructorNameNode) {
      this.constructorNameNodeList.add(constructorNameNode);
    }


    /**
     * @return the methodNameNodeList
     */
    public List<Node> getMethodNameNodeList() {
      return methodNameNodeList;
    }


    public boolean isInConditional() {
      return isInConditional;
    }


    public void setInConditional(boolean isInConditional) {
      this.isInConditional = isInConditional;
    }


    /**
     * @param methodNameNode
     *          the methodNameNodeList to set
     */
    public void addMethodNameNode(Node methodNameNode) {
      this.methodNameNodeList.add(methodNameNode);
    }


    /**
     * @return the qualifiedNameNodeList
     */
    public List<Node> getQualifiedNameNodeList() {
      return qualifiedNameNodeList;
    }


    /**
     * @param qualifiedNameNode
     *          the qualifiedNameNodeList to set
     */
    public void addQualifiedNameNode(Node qualifiedNameNode) {
      this.qualifiedNameNodeList.add(qualifiedNameNode);
    }
  }


  static final class MethodInjectionInfo {
    private String methodName;

    private List<String> parameterList;

    private Node injectionCall;


    public MethodInjectionInfo(String methodName, Node injectionCall) {
      this.methodName = methodName;
      this.injectionCall = injectionCall;
    }


    /**
     * @return the methodName
     */
    public String getMethodName() {
      return methodName;
    }


    /**
     * @param methodName
     *          the methodName to set
     */
    public void setMethodName(String methodName) {
      this.methodName = methodName;
    }


    /**
     * @return the isParameterSpecified
     */
    public boolean isParameterSpecified() {
      return parameterList != null;
    }


    /**
     * @return the parameterList
     */
    public List<String> getParameterList() {
      return parameterList;
    }


    public void setParameterList(List<String> parameterList) {
      this.parameterList = parameterList;
    }


    /**
     * @return the injectionCall
     */
    public Node getInjectionCall() {
      return injectionCall;
    }

  }


  static final class ConstructorInfo implements Cloneable {
    private String constructorName;

    private List<String> paramList = Lists.newArrayList();

    private List<ConstructorInfo> parentList = Lists.newArrayList();

    private List<MethodInjectionInfo> methodInjectionInfoList = Lists.newArrayList();

    private boolean hasInterceptor = false;

    private ScopeType scopeType;

    private Node constructorNode;

    private Map<String, PrototypeInfo> prototypeInfoMap = Maps.newHashMap();

    private Map<String, Node> injectedSingletonCallMap = Maps.newHashMap();

    private JSDocInfo jsDocInfo;

    private boolean constructorExtended = false;

    private Node aliasPoint;

    private Node singletonVariable;

    private boolean isDuplicated;

    private boolean hasConstructorInjectionSpecification;
    
    private boolean hasInstanceFactory = false;

    private BindingInfo bindingInfo;


    public ConstructorInfo(String constructorName) {
      this.constructorName = constructorName;
    }


    void rewriteConstructorName(String name) {
      this.constructorName = name;
    }


    public String getConstructorName() {
      return this.constructorName;
    }


    public void addParam(String name) {
      paramList.add(name);
    }


    public void setParamList(List<String> paramList) {
      this.paramList = paramList;
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
     * @return the methodInjectionInfoList
     */
    public List<MethodInjectionInfo> getMethodInjectionList() {
      return methodInjectionInfoList;
    }


    /**
     * @param methodInjectionInfoList
     *          the methodInjectionInfoList to set
     */
    public void setMethodInjectionInfoList(List<MethodInjectionInfo> methodInjectionInfoList) {
      this.methodInjectionInfoList.addAll(methodInjectionInfoList);
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


    /**
     * @return the constructorExtended
     */
    public boolean isConstructorExtended() {
      return constructorExtended;
    }


    /**
     * @param constructorExtended
     *          the constructorExtended to set
     */
    public void setConstructorExtended(boolean constructorExtended) {
      this.constructorExtended = constructorExtended;
    }


    /**
     * @return the aliasPoint
     */
    public Node getAliasPoint() {
      return aliasPoint;
    }


    /**
     * @param aliasPoint
     *          the aliasPoint to set
     */
    public void setAliasPoint(Node aliasPoint) {
      this.aliasPoint = aliasPoint;
    }


    /**
     * @return the singletonVariable
     */
    public Node getSingletonVariable() {
      return singletonVariable;
    }


    /**
     * @param singletonVariable
     *          the singletonVariable to set
     */
    public void setSingletonVariable(Node singletonVariable) {
      this.singletonVariable = singletonVariable;
    }


    public void setScopeType(ScopeType scopeType) {
      this.scopeType = scopeType;
    }


    public boolean isSingleton() {
      return this.scopeType == ScopeType.SINGLETON ||
          this.scopeType == ScopeType.EAGER_SINGLETON;
    }


    public boolean isEager() {
      return this.scopeType == ScopeType.EAGER_SINGLETON;
    }


    public void setDuplicated(boolean isDuplicated) {
      this.isDuplicated = isDuplicated;
    }


    public boolean isDuplicated() {
      return isDuplicated;
    }


    public void setBindingInfo(BindingInfo bindingInfo) {
      this.bindingInfo = bindingInfo;
    }


    public BindingInfo getBindingInfo() {
      return bindingInfo;
    }


    /**
     * @return the hasConstructorInjectionSpecification
     */
    public boolean hasConstructorInjectionSpecification() {
      return hasConstructorInjectionSpecification;
    }


    /**
     * @param hasConstructorInjectionSpecification
     *          the hasConstructorInjectionSpecification to set
     */
    public void setConstructorInjectionSpecification(boolean hasConstructorInjectionSpecification) {
      this.hasConstructorInjectionSpecification = hasConstructorInjectionSpecification;
    }


    public void addParent(ConstructorInfo parent) {
      this.parentList.add(parent);
    }


    public List<ConstructorInfo> getParentList() {
      return this.parentList;
    }


    @Override
    public Object clone() {
      try {
        ConstructorInfo info = (ConstructorInfo) super.clone();
        for (String key : this.prototypeInfoMap.keySet()) {
          PrototypeInfo proto = this.prototypeInfoMap.get(key);
          info.prototypeInfoMap.put(key, (PrototypeInfo) proto.clone());
        }

        return info;
      } catch (CloneNotSupportedException e) {
        throw new InternalError(e.toString());
      }
    }
    
    public void setHasInstanceFactory() {
      hasInstanceFactory = true;
    }
    
    public boolean hasInstanceFactory() {
      return hasInstanceFactory;
    }
  }


  static final class ModuleInitializerInfo {
    private List<String> configModuleList;

    private Node moduleInitCall;

    private List<InjectorInfo> injectorInfoList = Lists.newArrayList();


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


    public void addInjectorInfo(InjectorInfo injectorInfoList) {
      this.injectorInfoList.add(injectorInfoList);
    }


    public List<InjectorInfo> getInjectorInfoList() {
      return this.injectorInfoList;
    }
  }


  static final class ModuleInfo {

    private String moduleName;

    private ArrayListMultimap<String, BindingInfo> bindingInfoMap = ArrayListMultimap.create();

    private List<InterceptorInfo> interceptorInfoList = Lists.newArrayList();

    private Node moduleMethodNode;

    private boolean isProcessed;


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
    public ArrayListMultimap<String, BindingInfo> getBindingInfoMap() {
      return bindingInfoMap;
    }


    /**
     * @param bindingInfoMap
     *          the bindingInfoMap to set
     */
    public void setBindingInfoMap(ArrayListMultimap<String, BindingInfo> bindingInfoMap) {
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
     * @return the isProcessed
     */
    public boolean isProcessed() {
      return isProcessed;
    }


    /**
     * @param isProcessed
     *          the isProcessed to set
     */
    public void setProcessed(boolean processed) {
      this.isProcessed = processed;
    }


    /**
     * @return the interceptorInfoList
     */
    public List<InterceptorInfo> getInterceptorInfoList() {
      return interceptorInfoList;
    }


    /**
     * @param interceptorInfoList
     *          the interceptorInfoList to set
     */
    public void setInterceptorInfoList(List<InterceptorInfo> interceptorInfoList) {
      this.interceptorInfoList = interceptorInfoList;
    }
  }


  public static String getBindingTypeString() {
    return getMethodNameList(bindingTypeMap.keySet());
  }


  public static String getScopeTypeString() {
    return getMethodNameList(scopeTypeMap.keySet());
  }


  private static String getMethodNameList(Set<String> keySet) {
    StringBuilder builder = new StringBuilder();
    for (String methodName : keySet) {
      builder.append(methodName + "\n");
    }
    return builder.toString();
  }


  enum BindingType {
    TO,
    TO_INSTANCE,
    TO_PROVIDER
  }


  enum ScopeType {
    SINGLETON,
    EAGER_SINGLETON,
    PROTOTYPE
  }

  static final ImmutableBiMap<String, BindingType> bindingTypeMap =
      new ImmutableBiMap.Builder<String, BindingType>()
          .put("to", BindingType.TO)
          .put("toInstance", BindingType.TO_INSTANCE)
          .put("toProvider", BindingType.TO_PROVIDER)
          .build();

  static final ImmutableMap<String, ScopeType> scopeTypeMap =
      new ImmutableMap.Builder<String, ScopeType>()
          .put("camp.injections.Scopes.SINGLETON", ScopeType.SINGLETON)
          .put("camp.injections.Scopes.EAGER_SINGLETON", ScopeType.EAGER_SINGLETON)
          .put("camp.injections.Scopes.PROTOTYPE", ScopeType.PROTOTYPE)
          .build();


  static final class BindingInfo implements Cloneable {
    private Node bindCallNode;

    private String name;

    private Node bindedExpressionNode;

    private Node providerNode;

    private ConstructorInfo constructorInfo;

    private BindingType bindingType;

    private ScopeType scopeType = ScopeType.PROTOTYPE;

    private String moduleVariableName;

    private boolean isInConditional;

    private boolean isRewrited;


    public BindingInfo(String name) {
      this.name = name;
    }


    /**
     * @return the name
     */
    public String getName() {
      return name;
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


    public void setBindCallNode(Node bindCallNode) {
      this.bindCallNode = bindCallNode;
    }


    public Node getBindCallNode() {
      return this.bindCallNode;
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


    public ConstructorInfo getConstructorInfo() {
      return constructorInfo;
    }


    public void setClassInfo(ConstructorInfo constructorInfo) {
      this.constructorInfo = constructorInfo;
    }


    /**
     * @return the bindingType
     */
    public BindingType getBindingType() {
      return bindingType;
    }


    /**
     * @param bindingType
     *          the bindingType to set
     */
    public void setBindingType(BindingType bindingType) {
      this.bindingType = bindingType;
    }


    /**
     * @return the scopeType
     */
    public ScopeType getScopeType() {
      return scopeType;
    }


    /**
     * @param scopeType
     *          the scopeType to set
     */
    public void setScopeType(ScopeType scopeType) {
      this.scopeType = scopeType;
    }


    public boolean isSingleton() {
      return this.scopeType == ScopeType.SINGLETON ||
          this.scopeType == ScopeType.EAGER_SINGLETON;
    }


    public boolean isProvider() {
      return this.bindingType == BindingType.TO_PROVIDER;
    }


    public boolean isEager() {
      return this.scopeType == ScopeType.EAGER_SINGLETON;
    }


    public void setModuleName(String moduleName) {
      this.moduleVariableName = DIProcessor.newValidVarName(moduleName);
    }


    public String getBindingAccessorName() {
      return DIConsts.BINDINGS_REPO_NAME + "." + DIProcessor.toGetter(name);
    }


    public void setInConditional(boolean isInConditional) {
      this.isInConditional = isInConditional;
    }


    public boolean isInConditional() {
      return isInConditional;
    }


    public boolean hasSameAttributes(BindingInfo bindingInfo) {
      return bindingInfo.getBindingType() == getBindingType() &&
          bindingInfo.getScopeType() == scopeType &&
          bindingInfo.getName() == name;
    }


    public boolean isRewrited() {
      return isRewrited;
    }


    public void setRewrited() {
      this.isRewrited = true;
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


  static final class InjectorInfo {
    private Node callNode;

    private List<ModuleInfo> moduleInfoList;

    private String classOrBindingName;

    private boolean isName = false;


    public InjectorInfo(Node callNode, String classOrBindingName, boolean isName) {
      this.callNode = callNode;
      this.classOrBindingName = classOrBindingName;
      this.isName = isName;
    }


    /**
     * @return the callNode
     */
    public Node getCallNode() {
      return callNode;
    }


    /**
     * @return the moduleInfoList
     */
    public List<ModuleInfo> getModuleInfoList() {
      return moduleInfoList;
    }


    /**
     * @param moduleInfoList
     *          the moduleInfoList to set
     */
    public void setModuleInfoList(List<ModuleInfo> moduleInfoList) {
      this.moduleInfoList = moduleInfoList;
    }


    public boolean isName() {
      return this.isName;
    }


    public String getName() {
      return classOrBindingName;
    }
  }


  static final class PrototypeInfo implements Cloneable {
    private Node methodNode;

    private List<String> paramList = Lists.newArrayList();

    private String name;

    private Set<InterceptorInfo> interceptorInfoSet = Sets.newLinkedHashSet();

    private boolean isAmbiguous = false;


    public PrototypeInfo(String name, Node function) {
      this.methodNode = function;
      this.name = name;
    }


    public void addParam(String name) {
      this.paramList.add(name);
    }


    public void addInterceptor(InterceptorInfo interceptorInfo) {
      this.interceptorInfoSet.add(interceptorInfo);
    }


    public Set<InterceptorInfo> getInterceptorInfoSet() {
      return this.interceptorInfoSet;
    }


    public boolean hasInterceptorInfo(InterceptorInfo interceptorInfo) {
      return this.interceptorInfoSet.contains(interceptorInfo);
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


    /**
     * @return the isAmbiguous
     */
    public boolean isAmbiguous() {
      return isAmbiguous;
    }


    /**
     * @param isAmbiguous
     *          the isAmbiguous to set
     */
    public void setAmbiguous(boolean isAmbiguous) {
      this.isAmbiguous = isAmbiguous;
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