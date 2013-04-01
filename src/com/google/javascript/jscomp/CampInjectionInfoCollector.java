package com.google.javascript.jscomp;

import java.util.List;
import java.util.Map;

import com.google.common.collect.Lists;
import com.google.common.base.Strings;
import com.google.javascript.jscomp.CampInjectionInfo.BindingInfo;
import com.google.javascript.jscomp.CampInjectionInfo.ClassInfo;
import com.google.javascript.jscomp.CampInjectionInfo.ModuleInfo;
import com.google.javascript.jscomp.CampInjectionInfo.ModuleInitializerInfo;
import com.google.javascript.jscomp.CampInjectionInfo.PrototypeInfo;
import com.google.javascript.jscomp.NodeTraversal.AbstractPostOrderCallback;
import com.google.javascript.rhino.JSDocInfo;
import com.google.javascript.rhino.JSTypeExpression;
import com.google.javascript.rhino.Node;
import com.google.javascript.rhino.Token;

final class CampInjectionInfoCollector {

  private static final DiagnosticType MESSAGE_FIRST_ARGUMENT_OF_MODULE_INITIALIZER_IS_INVALID = DiagnosticType
      .error(
          "JSC_MSG_FIRST_ARGUMENT_OF_MODULE_INITIALIZER_IS_INVALID.",
          "The first argument of camp.injections.modules.init must be a Array of camp.dependecies.Module implementation.");

  private static final DiagnosticType MESSAGE_FIRST_ARGUMENT_OF_MODULE_INITIALIZER_IS_EMPTY = DiagnosticType
      .warning(
          "JSC_MSG_FIRST_ARGUMENT_OF_MODULE_INITIALIZER_IS_EMPTY.",
          "The first argument of camp.injections.modules.init is empty.");

  private static final DiagnosticType MESSAGE_SECOND_ARGUMENT_OF_MODULE_INITIALIZER_IS_INVALID = DiagnosticType
      .error(
          "JSC_MSG_FIRST_ARGUMENT_OF_MODULE_INITIALIZER_IS_INVALID.",
          "The second argument of camp.injections.modules.init must be a function expression.");

  private static final DiagnosticType MESSAGE_FIRST_ARGUMENT_OF_INJECT_IS_INVALID = DiagnosticType
      .error(
          "JSC_MSG_FIRST_ARGUMENT_OF_INJECT_IS_INVALID.",
          "The first argument of camp.injections.Injector.inject must be a constructor function.");

  private static final DiagnosticType MESSAGE_SECOND_ARGUMENT_OF_INJECT_IS_INVALID = DiagnosticType
      .error(
          "JSC_MSG_SECOND_ARGUMENT_OF_INJECT_IS_INVALID.",
          "The second argument and rest of camp.injections.Injector.inject must be a string expression which is method name of injection target.");

  private AbstractCompiler compiler;

  private CampInjectionInfo campInjectionInfo = CampInjectionInfo.getInstance();


  public CampInjectionInfoCollector(AbstractCompiler compiler) {
    this.compiler = compiler;
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
            if (qualifiedName.indexOf("." + CampInjectionConsts.PROTOTYPE) > -1) {
              return new PrototypeMarkerProcessor(t, n);
            }
          }
        }
      } else if (n.isFunction()) {
        JSDocInfo info = NodeUtil.getBestJSDocInfo(n);
        if (info != null && info.isConstructor()) {
          return new ModuleOrConstructorMarkerProcessor(t, n);
        }
      } else if (n.isCall()) {
        if (n.getFirstChild().isGetProp()) {
          String qualifiedName = n.getFirstChild().getQualifiedName();
          if (qualifiedName != null) {
            if (qualifiedName.equals(CampInjectionConsts.MODULE_INIT_CALL)) {
              return new ModuleInitializerMarkerProcessor(t, n);
            } else if (qualifiedName.equals(CampInjectionConsts.SINGLETON_CALL)) {
              return new SingletonMarkerProcessor(t, n);
            } else if (qualifiedName.equals(CampInjectionConsts.INJECT_CALL)) {
              return new InjectMarkerProcessor(t, n);
            }
          }
        }
      }
      return null;
    }
  }


  private final class ModuleOrConstructorMarkerProcessor implements MarkerProcessor {
    private Node node;


    public ModuleOrConstructorMarkerProcessor(NodeTraversal t, Node n) {
      this.node = n;
    }


    public void processMarker() {
      if (this.isModule()) {
        this.processModule();
      } else {
        this.processConstructor();
      }
    }


    private boolean isModule() {
      JSDocInfo info = NodeUtil.getBestJSDocInfo(this.node);
      if (info != null && info.isConstructor() && info.getImplementedInterfaceCount() > 0) {
        List<JSTypeExpression> typeList = info.getImplementedInterfaces();
        for (JSTypeExpression jsType : typeList) {
          Node typeNode = jsType.getRoot();
          if (typeNode != null && typeNode.getType() == Token.BANG) {
            typeNode = typeNode.getFirstChild();
            if (typeNode.isString() && typeNode.getString() != null
                && typeNode.getString().equals(CampInjectionConsts.MODULE_INTERFACE)) {
              return true;
            }
          }
        }
      }
      return false;
    }


    private void processModule() {
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
        campInjectionInfo.putModuleInfo(moduleInfo);
      }
    }


    private void processConstructor() {
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
        classInfo.setConstructorNode(this.node);
        for (Node param : paramList.children()) {
          classInfo.addParam(param.getString());
        }
        campInjectionInfo.putClassInfo(classInfo);
      }
    }
  }


  private final class ModuleInitializerMarkerProcessor implements MarkerProcessor {
    private Node node;

    private NodeTraversal nodeTraversal;


    public ModuleInitializerMarkerProcessor(NodeTraversal t, Node n) {
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

          if (moduleList.isEmpty()) {
            this.nodeTraversal.report(this.node,
                MESSAGE_FIRST_ARGUMENT_OF_MODULE_INITIALIZER_IS_EMPTY);
          }

          Node closure = maybeConfig.getNext();

          if (closure != null && closure.isFunction()) {
            ModuleInitializerInfo moduleInitializerInfo = new ModuleInitializerInfo();
            moduleInitializerInfo.setConfigModuleList(moduleList);
            moduleInitializerInfo.setModuleInitCall(closure);
            campInjectionInfo.addModuleInitInfo(moduleInitializerInfo);
          } else {
            this.nodeTraversal.report(this.node,
                MESSAGE_SECOND_ARGUMENT_OF_MODULE_INITIALIZER_IS_INVALID);
          }

        } else {
          this.nodeTraversal.report(this.node,
              MESSAGE_FIRST_ARGUMENT_OF_MODULE_INITIALIZER_IS_INVALID);
        }
      } else {
        this.nodeTraversal.report(this.node,
            MESSAGE_FIRST_ARGUMENT_OF_MODULE_INITIALIZER_IS_INVALID);
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
        campInjectionInfo.putSetter(className, setterName);
        Node setterNameNode = classNameNode.getNext();
        while (setterNameNode != null) {
          setterNameNode = setterNameNode.getNext();
          setterName = this.getSetterName(setterNameNode);
          if (setterName != null) {
            campInjectionInfo.putSetter(className, setterName);
          } else {
            break;
          }
        }
        CampInjectionProcessor.detachStatement(this.node);
      } else {
        if (className == null) {
          this.nodeTraversal.report(this.node, MESSAGE_FIRST_ARGUMENT_OF_INJECT_IS_INVALID);
        }
        if (setterName == null) {
          this.nodeTraversal.report(this.node, MESSAGE_SECOND_ARGUMENT_OF_INJECT_IS_INVALID);
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


  private final class SingletonMarkerProcessor implements MarkerProcessor {
    private Node node;


    public SingletonMarkerProcessor(NodeTraversal t, Node n) {
      this.node = n;
    }


    public void processMarker() {
      Node arg = this.node.getFirstChild().getNext();
      if (arg != null) {
        String qualifiedName = arg.getQualifiedName();
        if (qualifiedName != null) {
          campInjectionInfo.putSingleton(qualifiedName, this.node);
        }
      }
    }
  }


  private final class PrototypeMarkerProcessor implements MarkerProcessor {
    private Node node;


    public PrototypeMarkerProcessor(NodeTraversal t, Node n) {
      this.node = n;
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
        if (qualifiedName.indexOf("." + CampInjectionConsts.PROTOTYPE) > -1) {
          String className = NodeUtil.getPrototypeClassName(lvalue).getQualifiedName();

          // foo.prototype.bar = function() {...
          if (rvalue.isFunction() && qualifiedName.matches(CampInjectionConsts.PROTOTYPE_REGEX)) {
            String methodName = NodeUtil.getPrototypePropertyName(lvalue);
            this.addPrototypeMember(className, methodName, rvalue);

          } else if (qualifiedName.endsWith("." + CampInjectionConsts.PROTOTYPE)
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
      campInjectionInfo.putPrototypeInfo(className, prototypeInfo);
    }
  }


  private final class InformationIntegrator {

    public void integrate() {
      this.bindClassInfo();
      this.bindBaseTypeInfo();
      this.bindModuleInfo();
    }


    private void bindClassInfo() {
      Map<String, ClassInfo> classInfoMap = campInjectionInfo.getClassInfoMap();
      for (String className : classInfoMap.keySet()) {
        ClassInfo classInfo = classInfoMap.get(className);
        Map<String, PrototypeInfo> prototypeInfoMap = campInjectionInfo
            .getPrototypeInfo(className);
        if (prototypeInfoMap != null) {
          classInfo.setPrototypeInfoMap(prototypeInfoMap);
        }

        if (campInjectionInfo.hasSetter(className)) {
          classInfo.setSetterList(campInjectionInfo.getSetterList(className));
        }

        classInfo.setSingletonCallNode(campInjectionInfo.getSingleton(className));
      }
    }


    private void bindBaseTypeInfo() {
      Map<String, ClassInfo> classInfoMap = campInjectionInfo.getClassInfoMap();
      for (ClassInfo classInfo : classInfoMap.values()) {
        this.checkBaseType(classInfo);
      }
    }


    private void checkBaseType(ClassInfo classInfo) {
      List<ClassInfo> classInfoList = this.getBaseTypeList(classInfo);
      Map<String, PrototypeInfo> prototypeInfoMap = classInfo.getPrototypeInfoMap();
      for (ClassInfo baseTypeInfo : classInfoList) {
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
              ClassInfo baseInfo = campInjectionInfo.getClassInfo(baseType);
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
      Map<String, ModuleInfo> moduleInfoMap = campInjectionInfo.getModuleInfoMap();
      for (String className : moduleInfoMap.keySet()) {
        ModuleInfo moduleInfo = moduleInfoMap.get(className);
        Map<String, BindingInfo> bindingInfoMap = campInjectionInfo.getBindingInfoMap(className);
        Map<String, PrototypeInfo> prototypeInfoMap = campInjectionInfo
            .getPrototypeInfo(className);
        if (prototypeInfoMap != null) {
          for (String methodName : prototypeInfoMap.keySet()) {
            PrototypeInfo prototypeInfo = prototypeInfoMap.get(methodName);
            if (methodName.equals(CampInjectionConsts.MODULE_SETUP_METHOD_NAME)) {
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
  }


  public void collectInfo(Node root) {
    NodeTraversal.traverse(compiler, root, new CollectCallback());
  }


  public void integrateInfo() {
    new InformationIntegrator().integrate();
  }


  private final class CollectCallback extends AbstractPostOrderCallback {

    private MarkerProcessorFactory factory = new MarkerProcessorFactory();


    @Override
    public void visit(NodeTraversal t, Node n, Node parent) {
      MarkerProcessor markerProcessor = factory.create(t, n);
      if (markerProcessor != null) {
        markerProcessor.processMarker();
      }
    }
  }
}
