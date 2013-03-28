package com.google.javascript.jscomp;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
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

  private static final String INJECT_CALL = "camp.dependencies.Injector.inject";

  private static final String CLASS_MATCHER_IN_NAMESPACE = "camp.dependencies.Matcher.inNamespace";

  private static final String CLASS_MATCHER_SUBCLASS_OF = "camp.dependencies.Matcher.subclassOf";

  private static final String CLASS_MATCHER_INSTANCE_OF = "camp.dependencies.Matcher.instanceOf";

  private static final String METHOD_MATCHER_LIKE = "camp.dependencies.Matcher.like";

  private static final String JOINT_POINT_AFTER = "camp.dependencies.JointPoint.AFTER";

  private static final String JOINT_POINT_BEFORE = "camp.dependencies.JointPoint.BEFORE";

  private static final String INTERCEPTOR_REGISTRY = "jscomp$interceptor$registry";

  private static final String INTERCEPTOR_NAME = "jscomp$interceptor$";

  private static final String GET_INJECTIED_INSTANCE = "jscomp$getInjectedInstance";

  private static final String INJECTED_INSTANCE = "_jscomp$injectedInstance";

  private static final String GOOG_PROVIDE_CALL = "goog.provide";

  private static final String SINGLETON_CALL = "goog.addSingletonGetter";

  private static final String MODULE_SETUP_METHOD_NAME = "configure";

  private static final String PROTOTYPE = "prototype";

  private static final String BIND = "bind";

  private static final String BIND_PROVIDER = "bindProvider";

  private static final String BIND_INTERCEPTOR = "bindInterceptor";

  private static final String CREATE_INSTANCE = "createInstance";

  private static final String THIS = "this";

  private static final String PROTOTYPE_REGEX = "(.*\\.prototype\\..*|.*\\.prototype$)";

  private int interceptorId = 0;

  private InjectionTargetInfo injectionTargetInfo = new InjectionTargetInfo();

  private final class InjectionTargetInfo {
    private Map<String, Map<String, PrototypeInfo>> prototypeInfoMap = Maps.newHashMap();

    private Map<String, ModuleInfo> moduleInfoMap = Maps.newHashMap();

    private List<ModuleInitInfo> moduleInitInfoList = Lists.newArrayList();

    private Map<String, ClassInfo> classInfoMap = Maps.newHashMap();

    private Map<String, Node> singletonMap = Maps.newHashMap();

    private Map<String, Map<String, BindingInfo>> bindingInfoMap = Maps.newHashMap();

    private Map<String, InterceptorInfo> interceptorInfoMap = Maps.newHashMap();

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

  private enum JointPointType {
    AFTER, BEFORE
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
     * @param interceptor
     *          the interceptor to set
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
      String setterName = this.getSetterName(classNameNode);
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

    private String getSetterName(Node beforeNode) {
      Node setterNameNode = beforeNode.getNext();
      if (setterNameNode != null && setterNameNode.isString()) {
        String name = setterNameNode.getString();
        if (Strings.isNullOrEmpty(name)) {
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
              moduleInfo.setModuleMethodNode(prototypeInfo.getFunction());
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
          NodeTraversal.traverseRoots(compiler, Lists.newArrayList(function), new RewriteCallback(
              binder, new BindCallRewriteProcessor(moduleInfo.getModuleName())));
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
        Node callSetup = new Node(Token.NEW, Node.newString(Token.NAME, config));
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

      public ModuleInitRewriteProcessor(List<String> moduleConfigList) {
        for (String moduleName : moduleConfigList) {
          ModuleInfo moduleInfo = injectionTargetInfo.getModuleInfo(moduleName);
          if (moduleInfo != null) {
            this.allBindingInfoMap.put(moduleInfo.getModuleName(), moduleInfo.getBindingInfoMap());
          }
        }
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
          Node child;
          if (info.getProvider() != null) {
            child = this.makeNewCallFromProvider(info.getProvider());
          } else {
            child = this.makeNewCall(info);
          }

          child.copyInformationFromForTree(createInstanceCall);
          if (child != null) {
            createInstanceCall.getParent().replaceChild(createInstanceCall, child);
          }
        }
      }

      private Node resolveBinding(String bindingName) {
        for (String className : this.allBindingInfoMap.keySet()) {
          Map<String, BindingInfo> bindingMap = this.allBindingInfoMap.get(className);
          if (bindingMap.containsKey(bindingName)) {
            BindingInfo bindingInfo = bindingMap.get(bindingName);
            Node entity = bindingInfo.getExpression();
            ClassInfo info = null;
            String name;
            Node ret = null;
            if (entity.isGetProp() && (name = entity.getQualifiedName()) != null) {
              info = injectionTargetInfo.getClassInfo(name);
            } else if (entity.isName() && (name = entity.getString()) != null) {
              info = injectionTargetInfo.getClassInfo(name);
            }

            if (info != null) {
              if (info.getProvider() != null) {
                ret = this.makeNewCallFromProvider(info.getProvider());
              } else {
                ret = this.makeNewCall(info);
              }
            } else {
              Node provider = bindingInfo.getProvider();
              if (provider != null) {
                ret = this.makeNewCallFromProvider(provider);
              } else {
                ret = createQualifiedNameNode(toLowerCase(getValidVarName(className)) + "."
                    + bindingName);
              }
            }
            return ret;
          }
        }
        return new Node(Token.NULL);
      }

      private Node makeNewCallFromProvider(Node function) {
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
              className, getInstanceMirror), new Node(Token.FUNCTION,
              Node.newString(Token.NAME, ""), new Node(Token.PARAM_LIST), new Node(Token.BLOCK,
                  new Node(Token.IF, new Node(Token.NOT, instanceHolder.cloneTree()),
                      instaniationBlock), new Node(Token.RETURN, instanceHolder.cloneTree())))));
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

    private final class BindCallRewriteProcessor implements NodeRewriteProcessor {
      String className;

      public BindCallRewriteProcessor(String className) {
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
                  while (n != null && !n.isExprResult()) {
                    n = n.getParent();
                  }
                  if (n != null) {
                    n.detachFromParent();
                  }
                }
              }
            }
          }
        }
      }

      private void caseInterceptor(NodeTraversal t, Node n) {
        Node classMatcher = n.getNext();
        if (classMatcher != null && classMatcher.isGetProp()) {
          Node methodMatcher = classMatcher.getNext();
          if (methodMatcher != null && methodMatcher.isGetProp()) {
            Node jointPoint = methodMatcher.getNext();
            if (jointPoint != null && jointPoint.isGetProp()) {
              Node interceptor = jointPoint.getNext();
              if (interceptor != null && interceptor.isFunction()) {
                InterceptorInfo interceptorInfo = this.getInterceptorInfo(classMatcher,
                    methodMatcher, jointPoint, interceptor);
                this.rewriteInterceptor(t, n, interceptorInfo.getInterceptor());
              }
            }
          }
        }
      }

      private InterceptorInfo getInterceptorInfo(Node classMatcher, Node methodMatcher,
          Node jointPoint, Node interceptor) {
        InterceptorInfo interceptorInfo = new InterceptorInfo();
        String classMatchTypeCallName = classMatcher.getQualifiedName();
        this.setClassMatchType(classMatchTypeCallName, classMatcher, interceptorInfo);
        String methodMatchTypeCallName = methodMatcher.getQualifiedName();
        this.setMethodMatchType(methodMatchTypeCallName, methodMatcher, interceptorInfo);
        String jointPointCall = jointPoint.getQualifiedName();
        this.setJointPoint(jointPointCall, jointPoint, interceptorInfo);
        interceptorInfo.setInterceptor(interceptor.cloneTree());
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

      private void setJointPoint(String jointPointCall, Node jointPoint,
          InterceptorInfo interceptorInfo) {
        if (jointPointCall.equals(JOINT_POINT_AFTER)) {
          interceptorInfo.setJoinPoint(JointPointType.AFTER);
        } else if (jointPointCall.equals(JOINT_POINT_BEFORE)) {
          interceptorInfo.setJoinPoint(JointPointType.BEFORE);
        }
      }

      private void rewriteBinding(NodeTraversal t, Node n, String bindingName, Node expression) {

        Node tmp = n;
        while (tmp != null && !tmp.isExprResult()) {
          tmp = tmp.getParent();
        }

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

      private void rewriteInterceptor(NodeTraversal t, Node n, Node function) {
        Node tmp = n;
        while (tmp != null && !tmp.isExprResult()) {
          tmp = tmp.getParent();
        }
        tmp.getParent().addChildAfter(
            new Node(Token.EXPR_RESULT, new Node(Token.ASSIGN,
                createQualifiedNameNode(INTERCEPTOR_REGISTRY + "." + INTERCEPTOR_NAME
                    + interceptorId++), function)), tmp);
        tmp.detachFromParent();
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
    return className.toLowerCase();
  }

  private static String getValidVarName(String className) {
    return className.replaceAll("\\.", "_");
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
