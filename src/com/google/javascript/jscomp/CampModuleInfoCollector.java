package com.google.javascript.jscomp;

import java.util.Set;

import com.google.common.base.Strings;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.google.javascript.jscomp.CampModuleTransformInfo.ModuleInfo;
import com.google.javascript.jscomp.NodeTraversal.AbstractPostOrderCallback;
import com.google.javascript.jscomp.Scope.Var;
import com.google.javascript.rhino.JSDocInfo;
import com.google.javascript.rhino.Node;

final class CampModuleInfoCollector {

  static final DiagnosticType MESSAGE_MODULE_NOT_ALLOWED_IN_CLOSURE = DiagnosticType.error(
      "JSC_MSG_MODULE_NOT_ALLOWED_IN_CLOSURE.",
      "The function 'camp.module' is only allowed in global scope.");

  static final DiagnosticType MESSAGE_MODULE_FIRST_ARGUMENT_NOT_VALID = DiagnosticType.error(
      "JSC_MSG_MODULE_FIRST_ARGUMENT_NOT_VALID.",
      "The first argument of the camp.module must be a string expression of a module name.");

  static final DiagnosticType MESSAGE_MODULE_SECOND_ARGUMENT_NOT_VALID = DiagnosticType
      .error(
          "JSC_MSG_MODULE_SECOND_ARGUMENT_NOT_VALID.",
          "The second argument of the camp.module must be a function which has an argument named <exports> only.");

  static final DiagnosticType MESSAGE_USING_FIRST_ARGUMENT_NOT_VALID = DiagnosticType.error(
      "JSC_MSG_USING_FIRST_ARGUMENT_NOT_VALID.",
      "The first argument of the camp.using must be a string expression of a module name.");

  static final DiagnosticType MESSAGE_DUPLICATE_USING = DiagnosticType.error(
      "JSC_MSG_USING_FIRST_ARGUMENT_NOT_VALID.",
      "The alias var name {0} is aleredy used.");

  static final DiagnosticType MESSAGE_MODULE_ONLY_ALLOWED_ONCE_PER_FILE = DiagnosticType
      .error(
          "JSC_MSG_MODULE_ONLY_ALLOWED_ONCE_PER_FILE",
          "The camp.module call is only allowed once per file.");

  static final DiagnosticType MESSAGE_INVALID_ACCESS_TO_ENTITY = DiagnosticType
      .error(
          "JSC_MSG_INVALID_ACCESS_TO_ENTITY.",
          "The {0} can not use as a object and can not access to itself because that inlined by compiler.");

  static final DiagnosticType MESSAGE_INVALID_USE_OF_USING = DiagnosticType
      .error(
          "JSC_MSG_INVALID_USE_OF_USING.",
          "The camp.using cannot call outside of camp.module.");

  static final DiagnosticType MESSAGE_INVALID_PARENT_OF_USING = DiagnosticType
      .error(
          "JSC_MSG_INVALID_PARENT_OF_USING.",
          "The camp.using must be used in variable decalaration or called directly under the camp.module scope.");

  static final DiagnosticType MESSAGE_MAIN_NOT_FUNCTION = DiagnosticType.error(
      "JSC_MSG_MODULE_EXPORTS_Main must be a function.",
      "exported main must be a function, variable or property.");

  static final DiagnosticType MESSAGE_MAIN_ALREADY_FOUNDED = DiagnosticType.error(
      "JSC_MSG_MAIN_ALREADY_FOUNDED.",
      "The function main allowed only one declaration per file. First defined {0}");

  static final DiagnosticType MESSAGE_MAIN_ONLY_ALLOWED_IN_ASSIGNMENT = DiagnosticType.error(
      "JSC_MSG_MAIN_ONLY_ALLOWED_IN_ASSIGNMENT.",
      "The function main only allowed to be left hand side of assignment.");

  private static final ImmutableSet<String> MARKER_SET = new ImmutableSet.Builder<String>()
      .add(CampModuleConsts.USING_CALL)
      .add(CampModuleConsts.CAMP_MODULE_CALL)
      .build();

  private final CampModuleTransformInfo campModuleTransformInfo;

  private final AbstractCompiler compiler;


  public CampModuleInfoCollector(AbstractCompiler compiler,
      CampModuleTransformInfo campModuleTransformInfo) {
    this.compiler = compiler;
    this.campModuleTransformInfo = campModuleTransformInfo;
  }


  public void process(Node root) {
    NodeTraversal.traverse(compiler, root, new ModuleCallFinder());
  }


  private boolean isAccessToMethod(NodeTraversal t, Node n, Node parent) {
    if (n.isGetProp() && !parent.isCall()) {
      if (parent.isAssign() && parent.getFirstChild().equals(n)) {
        return false;
      }
      String qualifiedName = n.getQualifiedName();
      if (MARKER_SET.contains(qualifiedName)) {
        t.report(n, MESSAGE_INVALID_ACCESS_TO_ENTITY, qualifiedName);
        return true;
      }
    }
    return false;
  }


  private boolean isAliasVar(NodeTraversal t, String varName) {
    Scope scope = t.getScope();
    Var var = scope.getVar(varName);
    if (var != null) {
      Node nameNode = var.getNameNode();
      Node child = nameNode.getFirstChild();
      if (child != null) {
        boolean inCall = false;
        while (true) {
          if (child != null) {
            if (child.isCall()) {
              inCall = true;
            }
            if (child.isGetProp() && inCall) {
              String qualifiedName = child.getQualifiedName();

              if (Strings.isNullOrEmpty(qualifiedName)) {
                return false;
              }

              if (qualifiedName.equals(CampModuleConsts.USING_CALL)) {
                return true;
              }
            }
          } else {
            break;
          }
          child = child.getFirstChild();
        }

      }
    }
    return false;
  }


  private interface MarkerProcessor {
    public void processMarker(NodeTraversal t, Node n, Node parent);
  }


  private final class MarkerProcessorFactory {
    private ModuleInfo moduleInfo;

    private UsingMarkerProcessor usingMarkerProcessor;

    private ExportsMarkerProcessor exportsMarkerProcessor;

    private VariableProcessor variableProcessor;


    public MarkerProcessorFactory(ModuleInfo moduleInfo) {
      this.moduleInfo = moduleInfo;
      this.usingMarkerProcessor = new UsingMarkerProcessor(moduleInfo);
      this.exportsMarkerProcessor = new ExportsMarkerProcessor(moduleInfo);
      this.variableProcessor = new VariableProcessor(moduleInfo);
    }


    public MarkerProcessor getProperMarkerProcessor(NodeTraversal t, Node n, Node parent) {
      if (n.isCall()) {
        Node firstChild = n.getFirstChild();
        if (firstChild.isGetProp()) {
          String qualifiedName = firstChild.getQualifiedName();
          if (!Strings.isNullOrEmpty(qualifiedName)
              && qualifiedName.equals(CampModuleConsts.USING_CALL)) {
            return this.usingMarkerProcessor;
          }
        }
      } else if (n.isName() &&
          !n.getParent().isParamList()) {
        if (NodeUtil.isGet(parent)) {
          if (!parent.getFirstChild().equals(n)) {
            return null;
          }
        }
        Scope scope = t.getScope();
        Var var = scope.getVar(n.getString());
        if (var != null) {
          Node nameNode = var.getNameNode();
          if (nameNode.equals(this.moduleInfo.getNamespaceReferenceArgument())) {
            return this.exportsMarkerProcessor;
          } else {
            return this.variableProcessor;
          }
        }
      }
      return null;
    }
  }


  private final class UsingMarkerProcessor implements MarkerProcessor {
    private final ModuleInfo moduleInfo;

    private Set<String> varNameSet = Sets.newHashSet();


    public UsingMarkerProcessor(ModuleInfo moduleInfo) {
      this.moduleInfo = moduleInfo;
    }


    public void processMarker(NodeTraversal t, Node n, Node parent) {
      Node stringNode = n.getFirstChild().getNext();
      if (stringNode == null ||
          !stringNode.isString() ||
          Strings.isNullOrEmpty(stringNode.getString())) {
        t.report(n, MESSAGE_USING_FIRST_ARGUMENT_NOT_VALID);
      }

      Node tmp = n;
      while (tmp != null && (!parent.isExprResult() && !tmp.isVar())) {
        tmp = tmp.getParent();
        if (tmp != null && !tmp.isName() && !tmp.isExprResult() && !NodeUtil.isGet(tmp)
            && !tmp.isVar()) {
          t.report(n, MESSAGE_INVALID_PARENT_OF_USING);
          return;
        }
      }

      if (tmp != null) {
        if (tmp.isVar()) {
          Node nameNode = tmp.getFirstChild();
          if (nameNode != null) {
            String name = nameNode.getString();
            if (!Strings.isNullOrEmpty(name) && varNameSet.contains(name)) {
              t.report(nameNode, MESSAGE_DUPLICATE_USING, name);
            }
          }
        }
      } else {
        t.report(n, MESSAGE_INVALID_PARENT_OF_USING);
      }
      moduleInfo.addUsingCall(n);
    }
  }


  private final class ExportsMarkerProcessor implements MarkerProcessor {
    private final ModuleInfo moduleInfo;


    public ExportsMarkerProcessor(ModuleInfo moduleInfo) {
      this.moduleInfo = moduleInfo;
    }


    public void processMarker(NodeTraversal t, Node n, Node parent) {
      Node tmp = parent;
      while (NodeUtil.isGet(tmp)) {
        tmp = tmp.getParent();
      }

      if (NodeUtil.isGet(tmp.getFirstChild())) {
        tmp = tmp.getFirstChild();
      }

      String qualifiedName = tmp.getQualifiedName();
      String mainQualifiedName = moduleInfo.getNamespaceReferenceArgument().getString() + "."
          + "main";
      if (qualifiedName.equals(mainQualifiedName)) {
        tmp = tmp.getParent();
        if (tmp.isAssign() && tmp.getParent().isExprResult()) {
          Node rvalue = tmp.getLastChild();
          if (moduleInfo.getMain() == null) {
            if (rvalue.isName() || rvalue.isFunction() || NodeUtil.isGet(rvalue)) {
              moduleInfo.setMain(rvalue);
            } else {
              t.report(rvalue, MESSAGE_MAIN_NOT_FUNCTION);
            }
          } else {
            t.report(rvalue, MESSAGE_MAIN_ALREADY_FOUNDED,
                String.valueOf(moduleInfo.getMain().getSourcePosition()));
          }
        } else {
          t.report(tmp, MESSAGE_MAIN_ONLY_ALLOWED_IN_ASSIGNMENT);
        }
      } else if (!this.moduleInfo.hasExports(tmp)) {
        this.moduleInfo.addExports(tmp);
      }
    }
  }


  private final class VariableProcessor implements MarkerProcessor {
    private ModuleInfo moduleInfo;


    public VariableProcessor(ModuleInfo moduleInfo) {
      this.moduleInfo = moduleInfo;
    }


    public void processMarker(NodeTraversal t, Node n, Node parent) {
      String varName = n.getString();
      boolean isAlias = isAliasVar(t, varName);
      if (!parent.isVar() && !parent.isFunction() && isAlias) {
        moduleInfo.addAliasVar(n);
      } else if (!isAlias) {
        Scope scope = t.getScope();
        if (scope.getDepth() == 1) {
          if (parent.isVar() || parent.isFunction()) {
            String name = n.getString();
            if (moduleInfo.getRenamedVar(name) == null) {
              moduleInfo.putRenamedVar(name, moduleInfo.getModuleId() + "_" + varName);
              moduleInfo.addRenameVarBaseDeclaration(n);
            }
          }
        }

        Var var = scope.getVar(varName);
        if (var != null) {
          moduleInfo.addRenameTarget(var.getNameNode(), n);
        }
      }
    }
  }


  private final class ModuleCallFinder extends AbstractPostOrderCallback {

    @Override
    public void visit(NodeTraversal t, Node n, Node parent) {
      if (!isAccessToMethod(t, n, parent)) {
        if (parent != null && parent.isCall() && n.isGetProp()) {
          String qualifiedName = n.getQualifiedName();
          if (!Strings.isNullOrEmpty(qualifiedName)) {
            if (qualifiedName.equals(CampModuleConsts.CAMP_MODULE_CALL)) {
              if (this.isValidModuleUsage(t, parent)) {
                String sourceName = t.getSourceName();
                if (!campModuleTransformInfo.hasModuleInfo(sourceName)) {
                  this.processModule(sourceName, t, n, parent);
                } else {
                  t.report(parent, MESSAGE_MODULE_ONLY_ALLOWED_ONCE_PER_FILE);
                }
              }
            } else if (qualifiedName.equals(CampModuleConsts.USING_CALL)) {
              this.checkUsingCall(t, n);
            }
          }
        }
      }
    }


    private void checkUsingCall(NodeTraversal t, Node n) {
      Node tmp = n;
      while (true) {
        if (tmp == null) {
          break;
        }
        if (tmp.isFunction()) {
          Node parent = tmp.getParent();
          if (parent.isCall()) {
            Node child = parent.getFirstChild();
            if (child.isGetProp()
                && child.getQualifiedName().equals(CampModuleConsts.CAMP_MODULE_CALL)) {
              return;
            }
          }
        }
        tmp = tmp.getParent();
      }
      t.report(n, MESSAGE_INVALID_USE_OF_USING);
    }


    private void processModule(String sourceName, NodeTraversal t, Node n, Node parent) {
      String moduleName = n.getNext().getString();
      if (!Strings.isNullOrEmpty(moduleName)) {
        String moduleId = this.createModuleIdFrom(moduleName);
        Node functionNode = parent.getLastChild();
        Node paramList = NodeUtil.getFunctionParameters(functionNode);
        if (paramList.getChildCount() == 1) {
          ModuleInfo moduleInfo = campModuleTransformInfo.createModuleInfo(moduleName, moduleId,
              parent,
              paramList.getFirstChild());
          campModuleTransformInfo.putModuleInfo(sourceName, moduleInfo);
          NodeTraversal.traverseRoots(compiler,
              Lists.newArrayList(functionNode),
              new ModuleVisitor(moduleInfo));
        }
      } else {
        t.report(parent, MESSAGE_MODULE_FIRST_ARGUMENT_NOT_VALID);
      }
    }


    private String createModuleIdFrom(String moduleName) {
      return moduleName.replaceAll("\\.", "_");
    }


    private boolean isValidModuleUsage(NodeTraversal t, Node n) {
      return checkModuleIsCalledInGlobalScope(t, n) && checkModuleUsageIsValid(t, n);
    }


    private boolean checkModuleUsageIsValid(NodeTraversal t, Node n) {
      Node firstArg = n.getFirstChild().getNext();
      if (firstArg != null && firstArg.isString()) {
        Node secondArg = firstArg.getNext();
        if (secondArg != null && secondArg.isFunction()) {
          Node paramList = NodeUtil.getFunctionParameters(secondArg);
          if (paramList.getChildCount() == 1) {
            return true;
          } else {
            t.report(secondArg, MESSAGE_MODULE_SECOND_ARGUMENT_NOT_VALID);
          }
        } else {
          t.report(secondArg, MESSAGE_MODULE_SECOND_ARGUMENT_NOT_VALID);
        }
      } else {
        t.report(firstArg, MESSAGE_MODULE_FIRST_ARGUMENT_NOT_VALID);
      }
      return false;
    }


    private boolean checkModuleIsCalledInGlobalScope(NodeTraversal t, Node n) {
      if (!n.getParent().isExprResult()) {
        t.report(n, MESSAGE_MODULE_NOT_ALLOWED_IN_CLOSURE);
        return false;
      }

      Node parent = n.getParent().getParent();

      while (parent != null) {
        if (!NodeUtil.isStatementBlock(parent)) {
          t.report(n, MESSAGE_MODULE_NOT_ALLOWED_IN_CLOSURE);
          return false;
        }
        parent = parent.getParent();
      }
      return true;
    }
  }


  private final class JSDocInfoCollector {
    private ModuleInfo moduleInfo;


    public JSDocInfoCollector(ModuleInfo moduleInfo) {
      this.moduleInfo = moduleInfo;
    }


    public void processJSDocInfo(NodeTraversal t, Node n) {
      JSDocInfo jsDocInfo = n.getJSDocInfo();
      if (jsDocInfo != null) {
        this.checkJSDocType(jsDocInfo, t);
      }
    }


    private void checkJSDocType(JSDocInfo jsDocInfo, NodeTraversal t) {
      for (Node typeNode : jsDocInfo.getTypeNodes()) {
        this.checkTypeNodeRecursive(typeNode, t);
      }
    }


    private void checkTypeNodeRecursive(Node typeNode, NodeTraversal t) {
      if (typeNode.isString()) {
        String type = typeNode.getString();
        Scope scope = t.getScope();

        if (!isExportedType(type, scope)) {
          this.processLocalOrAliasType(typeNode, t, type, scope);
        } else {
          if (!moduleInfo.hasExportedType(typeNode)) {
            moduleInfo.addExportedType(typeNode);
          }
        }
      }

      for (Node child = typeNode.getFirstChild(); child != null; child = child.getNext()) {
        this.checkTypeNodeRecursive(child, t);
      }
    }


    private void processLocalOrAliasType(Node typeNode, NodeTraversal t, String type, Scope scope) {
      String[] types = type.split("\\.");
      if (types.length > 1) {
        type = types[0];
      }
      Var var = scope.getVar(type);
      if (var != null && isAliasVar(t, var.getName())) {
        if (!moduleInfo.hasExportedType(typeNode)) {
          moduleInfo.addAliasType(typeNode);
        }
      } else {
        processLocalType(typeNode, type, scope, var);
      }
    }


    private void processLocalType(Node typeNode, String type, Scope scope, Var var) {
      Scope global = scope;
      while (global.getDepth() > 1) {
        global = global.getParent();
      }
      Var globalVar = global.getOwnSlot(type);
      if (globalVar != null &&
          globalVar.getNameNode().equals(var.getNameNode()) &&
          !moduleInfo.hasLocalType(typeNode)) {
        moduleInfo.addLocalType(typeNode);
      }
    }


    private boolean isExportedType(String type, Scope scope) {
      String[] splited = type.split("\\.");
      if (splited.length > 1) {
        String topLevel = splited[0];
        Var var = scope.getVar(topLevel);
        if (var != null) {
          if (moduleInfo.getNamespaceReferenceArgument().equals(var.getNameNode())) {
            return true;
          }
        }
      }
      return false;
    }
  }


  private final class ModuleVisitor extends AbstractPostOrderCallback {
    private MarkerProcessorFactory markerProcessorFactory;

    private JSDocInfoCollector jsDocInfoCollector;

    public ModuleVisitor(ModuleInfo moduleInfo) {
      this.markerProcessorFactory = new MarkerProcessorFactory(moduleInfo);
      this.jsDocInfoCollector = new JSDocInfoCollector(moduleInfo);
    }


    @Override
    public void visit(NodeTraversal t, Node n, Node parent) {
      this.jsDocInfoCollector.processJSDocInfo(t, n);
      if (!isAccessToMethod(t, n, parent)) {
        MarkerProcessor processor = this.markerProcessorFactory
            .getProperMarkerProcessor(t, n, parent);
        if (processor != null) {
          processor.processMarker(t, n, parent);
        }
      }
    }
  }
}
