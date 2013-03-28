package com.google.javascript.jscomp;

import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.common.base.Strings;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.javascript.jscomp.NodeTraversal.AbstractPostOrderCallback;
import com.google.javascript.jscomp.Scope.Var;
import com.google.javascript.rhino.JSDocInfo;
import com.google.javascript.rhino.JSTypeExpression;
import com.google.javascript.rhino.Node;
import com.google.javascript.rhino.Token;

public class CampInjectionProcessor {

  private static final String MODULE_INIT_CALL = "camp.dependencies.module.init";

  private static final String MODULE_INTERFACE = "camp.dependencies.Module";

  private static final String CLASS_MATCHER_IN_NAMESPACE = "camp.dependencies.Matcher.inNamespace";

  private static final String CLASS_MATCHER_SUBCLASS_OF = "camp.dependencies.Matcher.subclassOf";

  private static final String CLASS_MATCHER_INSTANCE_OF = "camp.dependencies.Matcher.instanceOf";

  private static final String METHOD_MATCHER_LIKE = "camp.dependencies.Matcher.like";

  private static final String JOINT_POINT_AFTER = "camp.dependencies.JointPoint.AFTER";

  private static final String JOINT_POINT_BEFORE = "camp.dependencies.JointPoint.BEFORE";
  
  private static final String INTERCEPTOR_REGISTRY = "jscomp$interceptor$registry";
  
  private static final String INTERCEPTOR_NAME = "jscomp$interceptor$";

  private static final String GOOG_PROVIDE_CALL = "goog.provide";

  private static final String SINGLETON_CALL = "goog.addSingletonGetter";

  private static final String MODULE_SETUP_METHOD_NAME = "configure";

  private static final String PROTOTYPE = "prototype";

  private static final String BIND = "bind";

  private static final String BIND_PROVIDER = "bindProvider";

  private static final String BIND_INTERCEPTOR = "bindInterceptor";

  private static final String BINDING_REGISTRY = "jscomp$dependencies$bindingRegistry";

  private static final String PROTOTYPE_REGEX = "(.*\\.prototype\\..*|.*\\.prototype$)";
  
  private int interceptorId = 0;

  private InjectionTargetInfo injectionTargetInfo = new InjectionTargetInfo();

  private final class InjectionTargetInfo {
    private Map<String, Map<String, PrototypeInfo>> prototypeInfoMap = Maps.newHashMap();

    private Map<String, ModuleInfo> moduleInfoMap = Maps.newHashMap();

    private List<ModuleInitInfo> moduleInitInfoList = Lists.newArrayList();

    private Map<String, ClassInfo> classInfoMap = Maps.newHashMap();

    private Map<String, Boolean> singletonMap = Maps.newHashMap();

    private Map<String, Map<String, BindingInfo>> bindingInfoMap = Maps.newHashMap();

    private Map<String, InterceptorInfo> interceptorInfoMap = Maps.newHashMap();


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


    public void putSingleton(String className) {
      this.singletonMap.put(className, true);
    }


    public boolean isSingleton(String className) {
      return this.singletonMap.containsKey(className);
    }


    public void putBindingInfo(String className, BindingInfo bindingInfo) {
      Map<String, BindingInfo> map;
      if (!this.bindingInfoMap.containsKey(className)) {
        map = Maps.newHashMap();
        this.bindingInfoMap.put(className, map);
        map.put(bindingInfo.getName(), bindingInfo);
      } else {
        map = this.bindingInfoMap.get(className);
        map.put(bindingInfo.getName(), bindingInfo);
      }
    }


    public Map<String, BindingInfo> getBindingInfoMap(String className) {
      return this.bindingInfoMap.get(className);
    }


    private void putInterceptorInfo(String className, InterceptorInfo interceptorInfo) {
      this.interceptorInfoMap.put(className, interceptorInfo);
    }


    private InterceptorInfo getInterceptorInfo(String className) {
      return this.interceptorInfoMap.get(className);
    }
  }

  private enum ClassMatchType {
    IN_NAMESPACE,
    SUBCLASS_OF,
    INSTANCE_OF
  }

  private enum MethodMatchType {
    LIKE
  }
  
  private enum JointPointType {
    AFTER,
    BEFORE
  }

  private final class InterceptorInfo {
    private String classMatcher;

    private String methodMatcher;

    private JointPointType joinPoint;

    private String name;

    private ClassMatchType classMatchType;

    private MethodMatchType methodMatchType;
    
    private Node interceptor;


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
    public JointPointType getJoinPoint() {
      return joinPoint;
    }


    /**
     * @param joinPoint
     *          the joinPoint to set
     */
    public void setJoinPoint(JointPointType joinPoint) {
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
     * @param interceptor the interceptor to set
     */
    public void setInterceptor(Node interceptor) {
      this.interceptor = interceptor;
    }
  }

  private final class ClassInfo {
    private String className;

    private List<String> paramList = Lists.newArrayList();

    private List<String> setterList;

    private Node provider;

    private boolean isSingleton;

    private Node singletonCallNode;

    private Map<String, PrototypeInfo> prototypeInfoMap;

    private List<BindingInfo> bindingInfoList = Lists.newArrayList();


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


    public void addBindingInfo(BindingInfo bindingInfo) {
      this.bindingInfoList.add(bindingInfo);
    }


    public List<BindingInfo> getBindingInfoList() {
      return this.bindingInfoList;
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
      this.node = node;
      this.nodeTraversal = t;
    }


    public void processMarker() {
      JSDocInfo info = NodeUtil.getBestJSDocInfo(this.node);
      if (info != null && info.isConstructor()) {
        if (info.getImplementedInterfaceCount() == 1) {
          List<JSTypeExpression> typeList = info.getImplementedInterfaces();
          Node typeNode = typeList.get(0).getRoot();
          if (typeNode.isString() && typeNode.getString() != null
              && typeNode.getString().equals(MODULE_INTERFACE)) {
            this.caseModule(info);
            return;
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
            if (module.isGetProp() || module.isName() && module.getQualifiedName() != null) {
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
          injectionTargetInfo.putSingleton(qualifiedName);
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
    public void rewrite();
  }

  private final class Rewriter {

    public void rewrite() {
      this.bindClassInfo();
      this.bindModuleInfo();
      this.rewriteMarkers();
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

        classInfo.setSingleton(injectionTargetInfo.isSingleton(className));
      }
    }


    private void bindModuleInfo() {
      Map<String, ModuleInfo> moduleInfoMap = injectionTargetInfo.getModuleInfoMap();
      for (String className : moduleInfoMap.keySet()) {
        ModuleInfo moduleInfo = moduleInfoMap.get(className);
        Map<String, PrototypeInfo> prototypeInfoMap = injectionTargetInfo
            .getPrototypeInfo(className);
        for (String methodName : prototypeInfoMap.keySet()) {
          PrototypeInfo prototypeInfo = prototypeInfoMap.get(methodName);
          if (methodName.equals(MODULE_SETUP_METHOD_NAME)) {
            moduleInfo.setModuleMethodNode(prototypeInfo.getFunction());
          }
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
          NodeTraversal.traverseRoots(compiler, Lists.newArrayList(function), new BinderRewriter(
              moduleInfo.getModuleName(), binder));
        }
      }
    }

    private final class BindCallRewriteProcessor implements NodeRewriteProcessor {
      private NodeTraversal t;

      private Node n;

      String className;


      public BindCallRewriteProcessor(NodeTraversal t, Node n, String className) {
        this.n = n;
        this.t = t;
        this.className = className;
      }


      public void rewrite() {
        String qualifiedName = this.n.getQualifiedName();
        String binderName = this.n.getFirstChild().getString();
        if (qualifiedName != null) {
          if (qualifiedName.equals(binderName + "." + BIND)) {
            this.caseBind();
          } else if (qualifiedName.equals(binderName + "." + BIND_PROVIDER)) {
            this.caseProvider();
          } else if (qualifiedName.equals(binderName + "." + BIND_INTERCEPTOR)) {
            this.caseInterceptor();
          }
        }
      }


      private void caseBind() {
        BindingInfo bindingInfo = new BindingInfo();
        Node bindNameNode = this.n.getNext();
        if (bindNameNode.isString()) {
          bindingInfo.setName(bindNameNode.getString());
          Node expressionNode = bindNameNode.getNext();
          if (expressionNode != null) {
            bindingInfo.setExpression(expressionNode);
            injectionTargetInfo.putBindingInfo(this.className, bindingInfo);
            this.rewriteBinding(bindNameNode.getString(), expressionNode);
          }
        }
      }


      private void caseProvider() {
        Node bindNameNode = this.n.getNext();
        BindingInfo bindingInfo = null;
        if (bindNameNode.isString()) {
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
              }
            }
          }
        }
      }


      private void caseInterceptor() {
        Node classMatcher = this.n.getNext();
        if (classMatcher != null && classMatcher.isGetProp()) {
          Node methodMatcher = classMatcher.getNext();
          if (methodMatcher != null && methodMatcher.isGetProp()) {
            Node jointPoint = methodMatcher.getNext();
            if (jointPoint != null && jointPoint.isGetProp()) {
              Node interceptor = jointPoint.getNext();
              if (interceptor != null && interceptor.isFunction()) {
                this.setInterceptorInfo(classMatcher, methodMatcher, jointPoint, interceptor);
              }
            }
          }
        }
      }


      private void setInterceptorInfo(Node classMatcher, Node methodMatcher, Node jointPoint,
          Node interceptor) {
        InterceptorInfo interceptorInfo = new InterceptorInfo();
        String classMatchTypeCallName = classMatcher.getQualifiedName();
        this.setClassMatchType(classMatchTypeCallName, classMatcher, interceptorInfo);
        String methodMatchTypeCallName = methodMatcher.getQualifiedName();
        this.setMethodMatchType(methodMatchTypeCallName, methodMatcher, interceptorInfo);
        String jointPointCall = jointPoint.getQualifiedName();
        this.setJointPoint(jointPointCall, jointPoint, interceptorInfo);
        interceptorInfo.setInterceptor(interceptor.cloneTree());
        injectionTargetInfo.putInterceptorInfo(this.className, interceptorInfo);
        this.rewriteInterceptor(interceptorInfo.getInterceptor());
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

      private void setJointPoint(String jointPointCall, Node jointPoint, InterceptorInfo interceptorInfo) {
        if (jointPointCall.equals(JOINT_POINT_AFTER)) {
          interceptorInfo.setJoinPoint(JointPointType.AFTER);
        } else if (jointPointCall.equals(JOINT_POINT_BEFORE)) {
          interceptorInfo.setJoinPoint(JointPointType.BEFORE);
        }
      }
      

      private void rewriteBinding(String bindingName, Node expression) {
        Node tmp = this.n;
        while (tmp != null && !tmp.isExprResult()) {
          tmp = tmp.getParent();
        }
        tmp.getParent().addChildAfter(new Node(Token.EXPR_RESULT, new Node(Token.ASSIGN,
            createQualifiedNameNode(BINDING_REGISTRY + "." + bindingName),
            expression.cloneTree())), tmp);
        tmp.detachFromParent();
      }
      
      private void rewriteInterceptor(Node function) {
        Node tmp = this.n;
        while (tmp != null && !tmp.isExprResult()) {
          tmp = tmp.getParent();
        }
        tmp.getParent().addChildAfter(new Node(Token.EXPR_RESULT, new Node(Token.ASSIGN,
            createQualifiedNameNode(INTERCEPTOR_REGISTRY + "." + INTERCEPTOR_NAME + interceptorId++),
            function)), tmp);
        tmp.detachFromParent();
      }
    }

    private final class BinderRewriter extends AbstractPostOrderCallback {
      private String className;

      private Node binder;


      public BinderRewriter(String className, Node binder) {
        this.className = className;
        this.binder = binder;
      }


      @Override
      public void visit(NodeTraversal t, Node n, Node parent) {
        Scope scope = t.getScope();
        if (parent.isCall()) {
          if (n.isGetProp()) {
            String name = n.getFirstChild().getString();
            Var var = scope.getVar(name);
            if (var != null) {
              Node initialValue = var.getInitialValue();
              if (initialValue.equals(this.binder)) {
                new BindCallRewriteProcessor(t, n, this.className).rewrite();
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
      markerProcessor.processMarker();
    }
  }


  public void process(Node extern, Node root) {
    NodeTraversal.traverse(compiler, root, new InformationCollector());
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
