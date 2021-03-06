package com.google.javascript.jscomp;

import java.util.List;
import java.util.Set;

import com.google.common.base.Strings;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.google.javascript.jscomp.CampModuleTransformInfo.JSDocLendsInfoMutator;
import com.google.javascript.jscomp.CampModuleTransformInfo.JSDocTypeInfoMutator;
import com.google.javascript.jscomp.CampModuleTransformInfo.LocalAliasInfo;
import com.google.javascript.jscomp.CampModuleTransformInfo.ModuleInfo;
import com.google.javascript.jscomp.CampModuleTransformInfo.TypeInfo;
import com.google.javascript.jscomp.NodeTraversal.AbstractPostOrderCallback;
import com.google.javascript.jscomp.Scope.Var;
import com.google.javascript.rhino.JSDocInfo;
import com.google.javascript.rhino.Node;
import com.google.javascript.rhino.Token;

/**
 * Collect information of the camp modules.
 * 
 * @author aono_taketoshi
 * 
 */
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
          "The second argument of the camp.module must be a function"
              +
              " or array literal which contains exported name.");

  static final DiagnosticType MESSAGE_USING_FIRST_ARGUMENT_NOT_VALID = DiagnosticType.error(
      "JSC_MSG_USING_FIRST_ARGUMENT_NOT_VALID.",
      "The first argument of the camp.using must be a string expression of a module name.");

  static final DiagnosticType MESSAGE_USING_SCOPE_IS_INVALID = DiagnosticType.error(
      "JSC_MSG_USING_SCOPE_IS_INVALID",
      "The camp.using must be called directly under the camp.module scope.");

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
          "The {0} can not use as an object and can not access to itself because that inlined by compiler.");

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
      "exported main must be a function, a variable or a property.");

  static final DiagnosticType MESSAGE_MAIN_ALREADY_FOUNDED = DiagnosticType.error(
      "JSC_MSG_MAIN_ALREADY_FOUNDED.",
      "The function main allowed only one declaration per file. First defined {0}");

  static final DiagnosticType MESSAGE_MAIN_ONLY_ALLOWED_IN_ASSIGNMENT = DiagnosticType.error(
      "JSC_MSG_MAIN_ONLY_ALLOWED_IN_ASSIGNMENT.",
      "The function main only allowed to be left hand side of assignment.");

  private static final ImmutableSet<String> MARKER_SET = ImmutableSet.of(
      CampModuleConsts.USING_CALL,
      CampModuleConsts.CAMP_MODULE_CALL);

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


  /**
   * Check property access of 'camp.module' and 'camp.using', because these
   * methods are inlining by transformer, so we can't treat property access for
   * these methods.
   * 
   * @param t
   *          Current NodeTraversal
   * @param n
   *          The target node.
   * @param parent
   *          The parent node of the target node.
   * @return true if property is accessed, otherwise false.
   */
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


  /**
   * Check whether a variable is alias of an external module or not. 'Alias'
   * means following form.
   * 
   * <pre>
   * <code>"var Alias = camp.using('foo.bar.baz.Alias');"</code>
   * </pre>
   * 
   * @param moduleInfo
   *          Current module information.
   * @param t
   *          Current NodeTraversal
   * @param varName
   *          A target variable name.
   * @return true if varName is alias of an external module, false otherwise.
   */
  private boolean isAliasVar(ModuleInfo moduleInfo, NodeTraversal t, String varName) {
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


  /**
   * The interface of camp style modules processor.
   * 
   * @author aono_taketoshi
   * 
   */
  private interface MarkerProcessor {
    /**
     * Collect information of the camp style modules specific methods calls.
     * 
     * @param t
     *          Current NodeTraversal
     * @param n
     *          The target node.
     * @param parent
     *          The parent node of the target node.
     */
    public void processMarker(NodeTraversal t, Node n, Node parent);
  }


  /**
   * The factory of the MarkerProcessors.
   * 
   * @author aono_taketoshi
   * 
   */
  private final class MarkerProcessorFactory {
    private ModuleInfo moduleInfo;

    private UsingMarkerProcessor usingMarkerProcessor;

    private ExportsMarkerProcessor exportsMarkerProcessor;

    private VariableProcessor variableProcessor;

    private TypeInfoProcessor typeInfoProcessor;

    private LocalAliasProcessor localAliasProcessor;


    public MarkerProcessorFactory(ModuleInfo moduleInfo) {
      this.moduleInfo = moduleInfo;
      this.usingMarkerProcessor = new UsingMarkerProcessor(moduleInfo);
      this.exportsMarkerProcessor = new ExportsMarkerProcessor(moduleInfo);
      this.variableProcessor = new VariableProcessor(moduleInfo);
      this.typeInfoProcessor = new TypeInfoProcessor(moduleInfo);
      this.localAliasProcessor = new LocalAliasProcessor(moduleInfo);
    }


    /**
     * Choose specific MarkerProcessor instance by current node type.
     * 
     * @param t
     *          Current NodeTraversal
     * @param n
     *          The target node.
     * @param parent
     *          The parent node of the target node.
     * @return The MarkerProcessor instance that is able to process target node.
     */
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
        if (parent.isGetProp()) {
          if (!parent.getFirstChild().equals(n)) {
            return null;
          }
        }

        if (parent.isGetElem()) {
          if (!parent.getLastChild().equals(n) &&
              !parent.getFirstChild().equals(n)) {
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
      } else if (n.isFunction()) {
        JSDocInfo info = NodeUtil.getBestJSDocInfo(n);
        if (info != null && info.isConstructor()) {
          return this.typeInfoProcessor;
        }
      } else if (n.isAssign() || n.isVar()) {
        return this.localAliasProcessor;
      }
      return null;
    }
  }


  /**
   * The 'camp.using' call processor.
   * 
   * @author aono_taketoshi
   * 
   */
  private final class UsingMarkerProcessor implements MarkerProcessor {
    private final ModuleInfo moduleInfo;

    private Set<String> varNameSet = Sets.newHashSet();


    public UsingMarkerProcessor(ModuleInfo moduleInfo) {
      this.moduleInfo = moduleInfo;
    }


    @Override
    public void processMarker(NodeTraversal t, Node n, Node parent) {
      if (t.getScopeDepth() != 2) {
        t.report(n, MESSAGE_USING_SCOPE_IS_INVALID);
        return;
      }
      Node stringNode = n.getFirstChild().getNext();
      if (stringNode == null ||
          !stringNode.isString() ||
          Strings.isNullOrEmpty(stringNode.getString())) {
        t.report(n, MESSAGE_USING_FIRST_ARGUMENT_NOT_VALID);
        return;
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


  /**
   * The namespace reference argument(like argument x of following code
   * 'camp.module(..., function(x){...})') property processor.
   * 
   * @author aono_taketoshi
   * 
   */
  private final class ExportsMarkerProcessor implements MarkerProcessor {
    private final ModuleInfo moduleInfo;


    public ExportsMarkerProcessor(ModuleInfo moduleInfo) {
      this.moduleInfo = moduleInfo;
    }


    @Override
    public void processMarker(NodeTraversal t, Node n, Node parent) {
      if (parent.isGetProp() && n.getNext().getString().equals("main")) {
        Node maybeAssign = parent.getParent();
        if (NodeUtil.isExprAssign(maybeAssign.getParent())) {
          Node rvalue = maybeAssign.getLastChild();
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
          t.report(parent, MESSAGE_MAIN_ONLY_ALLOWED_IN_ASSIGNMENT);
        }
      } else if (!this.moduleInfo.hasExports(n)) {
        this.moduleInfo.addExports(n);
      }
    }
  }


  /**
   * The local type alias processor.
   * 
   * @author aono_taketoshi
   * 
   */
  private final class LocalAliasProcessor implements MarkerProcessor {
    private ModuleInfo moduleInfo;


    public LocalAliasProcessor(ModuleInfo moduleInfo) {
      this.moduleInfo = moduleInfo;
    }


    @Override
    public void processMarker(NodeTraversal t, Node n, Node parent) {
      if (t.getScopeDepth() == 2) {
        Scope scope = t.getScope();
        Node lvalue = n.getFirstChild();

        if (n.isAssign()) {
          Node rvalue = lvalue.getNext();

          if (rvalue.isName() || NodeUtil.isGet(rvalue)) {
            addLocalAliasInfo(scope, n, lvalue, rvalue);
          }
        } else if (n.isVar()) {
          Node rvalue = lvalue.getFirstChild();
          if (rvalue != null && (rvalue.isName() || NodeUtil.isGet(rvalue))) {
            addLocalAliasInfo(scope, n, lvalue, rvalue);
          }
        }
      }
    }


    /**
     * Add local variable assignments to ModuleInfo.
     * 
     * @param scope
     *          Current scope.
     * @param n
     *          The target node.
     * @param lvalue
     *          The assignment left hand side value.
     * @param rvalue
     *          The assignment right hand side value.
     */
    private void addLocalAliasInfo(Scope scope, Node n, Node lvalue, Node rvalue) {
      String rvalueName = rvalue.getQualifiedName();

      if (rvalueName != null) {

        int index = rvalueName.indexOf(".");
        if (index > -1) {
          rvalueName = rvalueName.substring(index);
        }

        if (scope.getOwnSlot(rvalueName) != null) {
          LocalAliasInfo localAliasInfo = new LocalAliasInfo(n, lvalue.getQualifiedName(),
              rvalue.getQualifiedName());
          moduleInfo.addLocalAliasInfo(localAliasInfo);
        }
      }
    }
  }


  /**
   * The processor for JSDoc types.
   * 
   * @author aono_taketoshi
   * 
   */
  private final class TypeInfoProcessor implements MarkerProcessor {
    private ModuleInfo moduleInfo;


    public TypeInfoProcessor(ModuleInfo moduleInfo) {
      this.moduleInfo = moduleInfo;
    }


    @Override
    public void processMarker(NodeTraversal t, Node n, Node parent) {
      if (t.getScopeDepth() == 2) {
        JSDocInfo jsDocInfo = NodeUtil.getBestJSDocInfo(n);
        TypeInfo typeInfo = null;
        if (NodeUtil.isFunctionDeclaration(n)) {
          String name = n.getFirstChild().getString();
          typeInfo = new TypeInfo(name, n, jsDocInfo);
        } else {
          if (parent.isAssign()) {
            String name = parent.getFirstChild().getQualifiedName();
            typeInfo = new TypeInfo(name, n, jsDocInfo);
          } else if (NodeUtil.isVarDeclaration(parent)) {
            typeInfo = new TypeInfo(parent.getString(), n, jsDocInfo);
          }
        }

        if (typeInfo != null) {
          moduleInfo.setTypeInfo(typeInfo);
        }
      }
    }
  }


  /**
   * The processor for module local variables.
   * 
   * @author aono_taketoshi
   * 
   */
  private final class VariableProcessor implements MarkerProcessor {
    private ModuleInfo moduleInfo;


    public VariableProcessor(ModuleInfo moduleInfo) {
      this.moduleInfo = moduleInfo;
    }


    @Override
    public void processMarker(NodeTraversal t, Node n, Node parent) {
      String varName = n.getString();
      boolean isAlias = isAliasVar(moduleInfo, t, varName);

      if (t.getScopeDepth() == 2 &&
          (parent.isFunction() ||
          (parent.isVar() && !isAliasDecl(n.getFirstChild())))) {
        Scope scope = t.getScope();
        Var var = scope.getVar(varName);
        if (var != null && !var.getNameNode().equals(n) && isAliasDecl(var.getInitialValue())) {
          scope.undeclare(var);
          scope.declare(varName, n, null, compiler.getInput(n.getInputId()));
          isAlias = false;
        }
      }

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


    /**
     * Check whether variable is alias of the module local type or not.
     * 
     * @param rvalue
     *          The variable left hand side value node.
     * @return true if variable is alias of the module local type, otherwise
     *         false.
     */
    private boolean isAliasDecl(Node rvalue) {
      if (rvalue == null) {
        return false;
      }
      Node tmp = rvalue;
      while (true) {
        if (tmp.isCall()) {
          Node getprop = tmp.getFirstChild();
          if (getprop.isGetProp()) {
            String qualifiedName = getprop.getQualifiedName();
            if (qualifiedName != null && qualifiedName.equals(CampModuleConsts.USING_CALL)) {
              return true;
            }
          }
        }
        tmp = tmp.getFirstChild();
        if (tmp == null) {
          break;
        }
      }
      return false;
    }
  }


  /**
   * Find 'camp.module' call and process call.
   * 
   * @author aono_taketoshi
   * 
   */
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


    /**
     * Check whether 'camp.using' used outside of 'camp.module' or not.
     * 
     * @param t
     *          Current NodeTraversal.
     * @param n
     *          The target node.
     */
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


    /**
     * Create ModuleInfo from 'camp.module' call node.
     * 
     * @param sourceName
     *          Current processed filename.
     * @param t
     *          Current NodeTraversal
     * @param n
     *          The target node.
     * @param parent
     *          The parent node of the target node.
     */
    private void processModule(String sourceName, NodeTraversal t, Node n, Node parent) {
      String moduleName = n.getNext().getString();
      if (!Strings.isNullOrEmpty(moduleName)) {
        String moduleId = this.createModuleIdFrom(moduleName);
        List<String> exportedList = this.getExportedList(moduleName, n.getNext().getNext());
        Node functionNode = parent.getLastChild();
        Node paramList = NodeUtil.getFunctionParameters(functionNode);
        if (paramList.getChildCount() == 1) {
          ModuleInfo moduleInfo = campModuleTransformInfo.createModuleInfo(moduleName, moduleId,
              parent,
              paramList.getFirstChild());
          moduleInfo.setExportedList(exportedList);
          campModuleTransformInfo.putModuleInfo(sourceName, moduleInfo);
          NodeTraversal.traverseRoots(compiler,
              Lists.newArrayList(functionNode),
              new ModuleVisitor(moduleInfo));
        }
      } else {
        t.report(parent, MESSAGE_MODULE_FIRST_ARGUMENT_NOT_VALID);
      }
    }


    /**
     * Get list of the exported modules list. The exported module list is like
     * following code.
     * 
     * <pre>
     * <code>
     * camp.module('test.foo.bar.baz', ['Foo', 'Bar', 'Baz'], ...)
     * </code>
     * </pre>
     * 
     * The module 'Foo', 'Bar', 'Baz' is exported.
     * 
     * @param moduleName
     * @param maybeArray
     * @return
     */
    private List<String> getExportedList(String moduleName, Node maybeArray) {
      List<String> ret = Lists.newArrayList();
      if (maybeArray.isArrayLit()) {
        for (Node name : maybeArray.children()) {
          if (name.isString()) {
            ret.add(moduleName + "." + name.getString());
          }
        }
      }
      return ret;
    }


    /**
     * Create module id from full qualified module name.
     * 
     * @param moduleName
     *          Current module fullqualified name.
     * @return The module id.
     */
    private String createModuleIdFrom(String moduleName) {
      return moduleName.replaceAll("\\.", "_");
    }


    /**
     * Check whether 'camp.module' call is valid or not.
     * 
     * @param t
     *          Current NodeTraversal.
     * @param n
     *          The target node.
     * @return true if 'camp.module' call is valid, otherwise false.
     */
    private boolean isValidModuleUsage(NodeTraversal t, Node n) {
      return checkModuleIsCalledInGlobalScope(t, n) && checkModuleUsageIsValid(t, n);
    }


    /**
     * Check whether 'camp.module' call's arguments is valid or not.
     * 
     * @param t
     *          Current NodeTraversal.
     * @param n
     *          The target node.
     * @return true if 'camp.module' call's arguments is valid, otherwise false.
     */
    private boolean checkModuleUsageIsValid(NodeTraversal t, Node n) {
      Node firstArg = n.getFirstChild().getNext();
      if (firstArg != null && firstArg.isString()) {
        Node secondArg = firstArg.getNext();
        if (secondArg != null && (secondArg.isFunction() || secondArg.isArrayLit())) {
          if (secondArg.isArrayLit()) {
            secondArg = secondArg.getNext();
          }
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


    /**
     * Check whether 'camp.module' is called in global scopes.
     * 
     * @param t
     *          Current NodeTraversal.
     * @param n
     *          The 'camp.module' call node.
     * @return true if 'camp.module' is called in global scope, otherwise false.
     */
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


  /**
   * Collector of JSDocInfo nodes.
   * 
   * @author aono_taketoshi
   * 
   */
  private final class JSDocInfoCollector {
    private ModuleInfo moduleInfo;


    public JSDocInfoCollector(ModuleInfo moduleInfo) {
      this.moduleInfo = moduleInfo;
    }


    /**
     * Process JSDocInfo types.
     * 
     * @param t
     *          Current NodeTraversal.
     * @param n
     *          Target node.
     */
    public void processJSDocInfo(NodeTraversal t, Node n) {
      JSDocInfo jsDocInfo = n.getJSDocInfo();
      if (jsDocInfo != null) {
        this.checkJSDocType(jsDocInfo, t, n);
      }
    }


    /**
     * Get JSDocInfo types and set to ModuleInfo.
     * 
     * @param jsDocInfo
     *          JSDocInfo attached to target node.
     * @param t
     *          Current NodeTraversal.
     * @param n
     *          The target node.
     */
    private void checkJSDocType(JSDocInfo jsDocInfo, NodeTraversal t, Node n) {
      String lendsName = jsDocInfo.getLendsName();
      if (!Strings.isNullOrEmpty(lendsName)) {
        Scope scope = t.getScope();
        if (isExportedType(lendsName, scope)) {
          moduleInfo.addExportedType(new JSDocLendsInfoMutator(n, lendsName));
        } else if (isAliasType(t, scope, lendsName)) {
          moduleInfo.addAliasType(new JSDocLendsInfoMutator(n, lendsName));
        } else if (isLocalType(scope, lendsName)) {
          moduleInfo.addLocalType(new JSDocLendsInfoMutator(n, lendsName));
        }
      } else {
        for (Node typeNode : jsDocInfo.getTypeNodes()) {
          this.checkTypeNodeRecursive(typeNode, t);
        }
      }
    }


    /**
     * Get types from JSDocInfo recursively and add to the ModuleInfo.
     * 
     * @param typeNode
     *          Current type expression node.
     * @param t
     *          Current NodeTraversal.
     */
    private void checkTypeNodeRecursive(Node typeNode, NodeTraversal t) {
      Node parent = typeNode.getParent();
      boolean isRecordKey = false;

      if (parent != null) {
        if (parent.getType() == Token.COLON) {
          if (parent.getFirstChild().equals(typeNode)) {
            parent = parent.getParent();
            if (parent != null && parent.getType() == Token.LB) {
              isRecordKey = true;
            }
          }
        }
      }

      if (typeNode.isString() && !isRecordKey) {
        String type = typeNode.getString();
        Scope scope = t.getScope();

        if (isAliasType(t, scope, type)) {
          moduleInfo.addAliasType(new JSDocTypeInfoMutator(typeNode, type));
        } else if (isExportedType(type, scope)) {
          moduleInfo.addExportedType(new JSDocTypeInfoMutator(typeNode, type));
        } else if (isLocalType(scope, type)) {
          moduleInfo.addLocalType(new JSDocTypeInfoMutator(typeNode, type));
        }
      }

      for (Node child = typeNode.getFirstChild(); child != null; child = child.getNext()) {
        this.checkTypeNodeRecursive(child, t);
      }
    }


    /**
     * Check whether a type expression is alias of the 'camp.using' result or
     * not.
     * 
     * @param t
     *          Current NodeTraversal.
     * @param scope
     *          Current scope.
     * @param typeName
     *          Target type expression.
     * @return true if a type expression is alias of 'camp.using' call result,
     *         otherwise false.
     */
    private boolean isAliasType(NodeTraversal t, Scope scope, String typeName) {
      String type = getTopLevelName(typeName);

      Var var = scope.getVar(type);
      if (var != null && isAliasVar(moduleInfo, t, var.getName())) {
        return true;
      }
      return false;
    }


    /**
     * Check whether a type expression is the module local type or not.
     * 
     * @param scope
     *          Current scope.
     * @param typeName
     *          Target type expression.
     * @return true if a type expression is the module local type, otherwise
     *         false.
     */
    private boolean isLocalType(Scope scope, String typeName) {
      String type = getTopLevelName(typeName);

      Var var = scope.getVar(type);
      Scope global = scope;
      while (global.getDepth() > 1) {
        global = global.getParent();
      }
      Var globalVar = global.getOwnSlot(type);
      if (globalVar != null &&
          globalVar.getNameNode().equals(var.getNameNode())) {
        return true;
      }

      return false;
    }


    /**
     * Get a root property name of the type expressions like 'Foo.Bar.Baz' =>
     * 'Foo'.
     * 
     * @param type
     *          Type expression.
     * @return A root property name of the type property.
     */
    private String getTopLevelName(String type) {
      String[] types = type.split("\\.");
      return types[0];
    }


    /**
     * Check whether type expression includes namespace reference argument.
     * 
     * @param type
     *          Type expression.
     * @param scope
     *          Current scope.
     * @return true if type expression includes namespace reference argument,
     *         otherwise false.
     */
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


  /**
   * The visitor implementation for 'camp.module'.
   * 
   * @author aono_taketoshi
   * 
   */
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
