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

  private AbstractCompiler compiler;

  private CampInjectionInfo campInjectionInfo;


  public CampInjectionInfoCollector(AbstractCompiler compiler, CampInjectionInfo campInjectionInfo) {
    this.compiler = compiler;
    this.campInjectionInfo = campInjectionInfo;
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
            if (qualifiedName.indexOf("." + CampInjectionConsts.PROTOTYPE) > -1) {
              return this.prototypeMarkerProcessor;
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
            if (qualifiedName.equals(CampInjectionConsts.MODULE_INIT_CALL)) {
              return this.moduleInitializerProcessor;
            } else if (qualifiedName.equals(CampInjectionConsts.INJECT_CALL)) {
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
                && typeNode.getString().equals(CampInjectionConsts.MODULE_INTERFACE)) {
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
        campInjectionInfo.putModuleInfo(moduleInfo);
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
        campInjectionInfo.putClassInfo(classInfo);
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
            ModuleInitializerInfo moduleInitializerInfo = new ModuleInitializerInfo();
            moduleInitializerInfo.setConfigModuleList(moduleList);
            moduleInitializerInfo.setModuleInitCall(closure);
            campInjectionInfo.addModuleInitInfo(moduleInitializerInfo);
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
        CampInjectionProcessor.detachStatement(n);
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
      }
    }


    private void bindBaseTypeInfo() {
      Map<String, ClassInfo> classInfoMap = campInjectionInfo.getClassInfoMap();
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
          ClassInfo info = campInjectionInfo.getClassInfo(name);
          if (info != null) {
            this.createAliasClassInfoFrom(n, info, qualifiedName);
          }
        }
      }
    }


    private void createAliasClassInfoFrom(Node aliasPoint, ClassInfo info, String name) {
      if (campInjectionInfo.getClassInfo(name) == null) {
        ClassInfo aliasInfo = new ClassInfo(name);
        campInjectionInfo.putClassInfo(aliasInfo);
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
        ClassInfo info = campInjectionInfo.getClassInfo(name);
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
