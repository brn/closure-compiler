package com.google.javascript.jscomp;

import java.util.List;
import java.util.Map;
import java.util.Set;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.javascript.jscomp.CampModuleTransformInfo.JSDocMutator;
import com.google.javascript.jscomp.CampModuleTransformInfo.JSDocTypeInfoMutator;
import com.google.javascript.jscomp.CampModuleTransformInfo.LocalAliasInfo;
import com.google.javascript.jscomp.CampModuleTransformInfo.ModuleInfo;
import com.google.javascript.jscomp.CampModuleTransformInfo.TypeInfo;
import com.google.javascript.jscomp.CampModuleTransformInfo.VarRenamePair;
import com.google.javascript.rhino.JSDocInfo;
import com.google.javascript.rhino.JSDocInfoBuilder;
import com.google.javascript.rhino.JSTypeExpression;
import com.google.javascript.rhino.Node;
import com.google.javascript.rhino.Token;

/**
 * This class rewrite all camp style module codes to google closure style codes.
 * 
 * Rewrite below code
 * 
 * <pre>
 * <code>camp.module('foo.bar.baz.module', ['Point2D', 'Point3D'], function(exports) {
 *   var PValue = camp.using('foo.bar.baz.module.PointValue');
 *   \/**
 *   * \@constructor
 *   * \@param {PValue} x
 *   * \@param {PValue} y
 *   *\/
 *   exports.Point2D = function(x, y) {
 *     this.x = x;
 *     this.y = y;
 *   }
 *   
 *   \/**
 *   * \@constructor
 *   * \@param {PValue} x
 *   * \@param {PValue} y
 *   * \@param {Pvalue} z
 *   * \@extends {exports.Point2D}
 *   *\/
 *   exports.Point3D = function(x, y, z) {
 *     exports.Point3D.base(this, 'constructor', x, y);
 *     this._z = z;
 *   }   
 *   
 *   // module local variable.
 *   var LocalClass = function() {}
 *   
 *   // module type alias.
 *   exports.AliasPoint3D = exports.Point3D;
 * });
 * </code>
 * </pre>
 * 
 * as
 * 
 * <pre>
 * <code>goog.provide('foo.bar.baz.module.Point2D');
 * goog.provide('foo.bar.baz.module.Point3D');
 * 
 * goog.require('foo.bar.baz.module.PointValue');
 * \/**
 * * \@constructor
 * * \@param {foo.bar.baz.module.PointValue} x
 * * \@param {foo.bar.baz.module.PointValue} y
 * *\/
 * foo.bar.baz.module.Point2D = function(x, y) {
 *  this.x = x;
 *   this.y = y;
 * }
 * 
 * \/**
 * * \@constructor
 * * \@param {foo.bar.baz.module.PointValue} x
 * * \@param {foo.bar.baz.module.PointValue} y
 * * \@param {foo.bar.baz.module.PointValue} z
 * * \@extends {foo.bar.baz.module.Point2D}
 * *\/
 * foo.bar.baz.module.Point3D = function(x, y, z) {
 *   exports.Point3D.base(this, 'constructor', x, y);
 *   this._z = z;
 * }
 * 
 * // module local variables.
 * var foo_bar_baz_module_0_LocalClass = function() {}
 * 
 * // module type alias.
 * \/*
 * * \@type {foo.bar.baz.module.Point3D} 
 * \/*
 * exports.AliasPoint3D = exports.Point3D;
 * </pre>
 * 
 * <code>
 * 
 * @author aono_taketoshi
 */
public class CampModuleRewriter {

  private final AbstractCompiler compiler;

  private final CodingConvention convention;

  private final CampModuleTransformInfo campModuleTransformInfo;

  private final RewritePassExecutor rewritePassExecutor = new RewritePassExecutor();


  /**
   * Constructor.
   * 
   * @param compiler
   *          The compiler class.
   * @param campModuleTransformInfo
   *          The module information holder.
   */
  public CampModuleRewriter(AbstractCompiler compiler,
      CampModuleTransformInfo campModuleTransformInfo) {
    this.compiler = compiler;
    this.convention = compiler.getCodingConvention();
    this.campModuleTransformInfo = campModuleTransformInfo;
  }


  /**
   * Rewrite all camp style modules to google closure library style module.
   */
  public void process() {
    for (ModuleInfo moduleInfo : campModuleTransformInfo.getModuleInfoMap().values()) {
      this.rewritePassExecutor.execute(moduleInfo);
    }
  }


  /**
   * Rewrite all codes using Rewriter.
   * 
   * @author aono_taketoshi
   * 
   */
  private final class RewritePassExecutor {
    /**
     * Code rewrite pass.
     */
    private final ImmutableList<Rewriter> rewritePass = ImmutableList.of(
        new UsingCallRewriter(),
        new ExportsRewriter(),
        new VariableRewriter(),
        new JSDocRewriter(),
        new ModuleRewriter());


    /**
     * Call rewrite method of the Rewriters.
     * 
     * @param moduleInfo
     */
    public void execute(ModuleInfo moduleInfo) {
      for (Rewriter rewriter : rewritePass) {
        rewriter.rewrite(moduleInfo);
      }
    }
  }


  /**
   * The interface of the Code and JSDoc rewriter.
   * 
   * @author aono_taketoshi
   * 
   */
  private interface Rewriter {
    public void rewrite(ModuleInfo moduleInfo);
  }


  /**
   * Rewrite 'camp.using' call to 'goog.require' code.
   * 
   * @author aono_taketoshi
   * 
   */
  private final class UsingCallRewriter implements Rewriter {
    private Set<Node> replacedSet = Sets.newHashSet();


    @Override
    public void rewrite(ModuleInfo moduleInfo) {
      for (Node usingCall : moduleInfo.getUsingCallList()) {
        rewriteUsing(moduleInfo, usingCall);
      }

    }


    /**
     * Rewrite 'camp.using' call to 'goog.requrie' call.
     * 
     * @param moduleInfo
     *          Current module information.
     * @param usingCall
     *          The 'camp.using' call node.
     */
    private void rewriteUsing(ModuleInfo moduleInfo, Node usingCall) {
      Node parent = usingCall.getParent();
      while (parent != null && (!parent.isExprResult() && !parent.isVar())) {
        parent = parent.getParent();
      }

      Node nameNode = this.addGoogRequire(usingCall, parent);

      String qualifiedName = nameNode.getString();

      if (parent.isVar()) {
        String varName = parent.getFirstChild().getString();
        if (moduleInfo.getAliasName(varName) == null) {
          moduleInfo.putAliasName(varName, qualifiedName);
        }
      }

      this.rewriteVars(moduleInfo, usingCall,
          NodeUtil.newQualifiedNameNode(convention, nameNode.getString()), parent);
      parent.detachFromParent();
      compiler.reportCodeChange();
    }


    /**
     * Append 'goog.require' call to the file head.
     * 
     * @param usingCall
     *          The 'camp.using' call node.
     * @param parent
     *          The parent node of the 'camp.using' node.
     * @return The full qualified name of the required module.
     */
    private Node addGoogRequire(Node usingCall, Node parent) {
      Node nameNode = Node.newString(usingCall.getFirstChild().getNext().getString());
      Node requireCall = NodeUtil.newQualifiedNameNode(convention, CampModuleConsts.GOOG_REQUIRE);
      Node callNode = NodeUtil.newCallNode(requireCall, nameNode);
      Node expr = NodeUtil.newExpr(callNode);

      expr.copyInformationFromForTree(parent);
      parent.getParent().addChildAfter(expr, parent);
      compiler.reportCodeChange();
      return nameNode;
    }


    /**
     * Rewrite all 'camp.using' aliased variables to the full qualified module
     * name.
     * 
     * @param moduleInfo
     *          Current module information.
     * @param usingCall
     *          The 'camp.using' call node.
     * @param nameNode
     *          Full qualified name.
     * @param parent
     *          The parent node of the 'camp.using' call node.
     */
    private void rewriteVars(ModuleInfo moduleInfo, Node usingCall, Node nameNode, Node parent) {
      usingCall.getParent().replaceChild(usingCall, nameNode);
      if (parent != null && parent.isVar()) {
        Node varNameNode = parent.getFirstChild();
        String varName = varNameNode.getString();
        List<Node> aliasVarList = moduleInfo.getAliasVarList();
        for (Node target : aliasVarList) {
          String name = target.getString();
          if (!moduleInfo.isForbiddenAlias(name) &&
              name.equals(varName) && !replacedSet.contains(target)) {
            this.replaceAlias(moduleInfo, nameNode, varNameNode, varName, target);
            replacedSet.add(target);
          }
        }
      }
    }


    /**
     * Replace all aliased variables to full qualified module name.
     * 
     * @param moduleInfo
     *          Current module information.
     * @param nameNode
     *          Full qualified name node.
     * @param varNameNode
     *          The variable name node.
     * @param varName
     *          The variable name.
     * @param target
     *          The variable node.
     */
    private void replaceAlias(
        ModuleInfo moduleInfo,
        Node nameNode,
        Node varNameNode,
        String varName,
        Node target) {

      Node replaced = varNameNode.getFirstChild();
      Node clone = replaced.cloneTree();
      clone.copyInformationFromForTree(target);
      target.getParent().replaceChild(target, clone);
      compiler.reportCodeChange();
    }
  }


  /**
   * Rewrite namespace referenced argument to the current module full qualified
   * name.
   * 
   * @author aono_taketoshi
   * 
   */
  private final class ExportsRewriter implements Rewriter {

    /**
     * Resolver for all local alias variables.
     */
    private final LocalAliasResolver localAliasResolver = new LocalAliasResolver();


    /**
     * Attach type annotations to the alias declarations node to more
     * efficiently optimize code.
     * 
     * @author aono_taketoshi
     * 
     */
    private final class LocalAliasResolver {
      private Map<String, TypeInfo> inferedTypeInfoMap = Maps.newHashMap();


      /**
       * Attach type annotations to the alias declarations node.
       * 
       * @param moduleInfo
       *          Current module information.
       */
      public void resolve(ModuleInfo moduleInfo) {
        List<LocalAliasInfo> list = moduleInfo.getLocalAliasInfoList();
        for (LocalAliasInfo localAliasInfo : list) {
          String rvalueName = localAliasInfo.getRvalue();
          TypeInfo typeInfo = moduleInfo.getTypeInfo(rvalueName);

          if (typeInfo == null) {
            typeInfo = this.inferedTypeInfoMap.get(rvalueName);
          }

          if (typeInfo != null) {
            attachJSDocInfo(localAliasInfo, rvalueName, typeInfo, moduleInfo);
          }
        }
      }


      /**
       * Attach JSDoc to the alias declaration node.
       * 
       * @param localAliasInfo
       *          Alias information of current module.
       * @param rvalueName
       *          Right hand side value of the assignment node.
       * @param typeInfo
       *          The right hand side value type.
       * @param moduleInfo
       *          Current module information.
       */
      private void attachJSDocInfo(
          LocalAliasInfo localAliasInfo,
          String rvalueName,
          TypeInfo typeInfo,
          ModuleInfo moduleInfo) {

        inferedTypeInfoMap.put(localAliasInfo.getLvalue(), typeInfo);

        JSDocInfoBuilder builder = new JSDocInfoBuilder(false);
        Node functionType = buildJSDocTypeNode(rvalueName, typeInfo);

        Node assign = localAliasInfo.getNode();
        functionType.copyInformationFromForTree(assign);
        builder.recordType(new JSTypeExpression(functionType, assign.getSourceFileName()));
        assign.setJSDocInfo(builder.build(assign));
        Node target = functionType.getFirstChild().getFirstChild();
        moduleInfo.addLocalType(new JSDocTypeInfoMutator(target, rvalueName));
        compiler.reportCodeChange();
      }


      /**
       * Build type node of the JSDoc annotation.
       * 
       * @param rvalueName
       *          The right hand side value of the assigment.
       * @param typeInfo
       *          Aliased type information.
       * @return The constructor type node.
       */
      private Node buildJSDocTypeNode(String rvalueName, TypeInfo typeInfo) {
        Node paramTypeList = new Node(Token.PARAM_LIST);
        Node functionType = new Node(Token.FUNCTION, new Node(Token.NEW,
            Node.newString(rvalueName)));
        JSDocInfo info = typeInfo.getJSDocInfo();

        for (String paramName : info.getParameterNames()) {
          JSTypeExpression exp = info.getParameterType(paramName);
          Node typeNode = exp.getRoot();
          if (exp != null && typeNode != null) {
            Node clone = typeNode.cloneTree();
            clone.copyInformationFromForTree(typeNode);
            paramTypeList.addChildToBack(clone);
          }
        }

        if (paramTypeList.getChildCount() > 0) {
          functionType.addChildToBack(paramTypeList);
        }

        functionType.addChildToBack(new Node(Token.QMARK));
        return functionType;
      }

    }


    @Override
    public void rewrite(ModuleInfo moduleInfo) {
      String moduleName = moduleInfo.getModuleName();
      Set<Node> exportsSet = moduleInfo.getExportsSet();
      for (Node exports : exportsSet) {
        rewriteExports(moduleInfo, moduleName, exports);
      }

      rewriteMain(moduleInfo);

      compiler.reportCodeChange();

      localAliasResolver.resolve(moduleInfo);
    }


    /**
     * Rewrite namespace referenced node to the full qualified module name.
     * 
     * @param moduleInfo
     *          Current module information.
     * @param moduleName
     *          Current module name.
     * @param exports
     *          The namespace referenced node.
     */
    private void rewriteExports(ModuleInfo moduleInfo, String moduleName, Node exports) {
      Node tmp = exports;

      while (true) {
        Node parent = tmp.getParent();
        if (NodeUtil.isGet(parent)) {
          tmp = parent;
        } else {
          break;
        }
      }

      Node fqn = NodeUtil.newQualifiedNameNode(convention, moduleInfo.getModuleName());
      fqn.copyInformationFromForTree(exports);
      exports.getParent().replaceChild(exports, fqn);
      compiler.reportCodeChange();
    }


    /**
     * Rewrite module main method.
     * 
     * @param moduleInfo
     *          Current module information.
     */
    private void rewriteMain(ModuleInfo moduleInfo) {
      Node main = moduleInfo.getMain();

      if (main != null) {
        Node assign = main.getParent();
        main.detachFromParent();
        Node call = NodeUtil.newCallNode(main);
        assign.getParent().replaceChild(assign, call);
        Node expr = call.getParent();
        expr.detachFromParent();
        moduleInfo.getModuleCallNode().getLastChild().getLastChild().addChildToBack(expr);
        compiler.reportCodeChange();
      }
    }
  }


  /**
   * Rewrite module local variables to the global unique variable.
   * 
   * @author aono_taketoshi
   * 
   */
  private final class VariableRewriter implements Rewriter {

    @Override
    public void rewrite(ModuleInfo moduleInfo) {
      List<VarRenamePair> variableList = moduleInfo.getRenameTargetList();
      for (VarRenamePair varRenamePair : variableList) {
        Node declaration = varRenamePair.getBaseDeclaration();
        Node target = varRenamePair.getTargetNode();
        rewriteVarName(moduleInfo, declaration, target);
      }
    }


    /**
     * Rewrite variable name to global unique variable name.
     * 
     * @param moduleInfo
     * @param declaration
     * @param target
     */
    private void rewriteVarName(ModuleInfo moduleInfo, Node declaration, Node target) {
      if (moduleInfo.isRenameVarBaseDeclaration(declaration)) {
        String after = moduleInfo.getRenamedVar(target.getString());
        if (after != null) {
          target.setString(after);
          compiler.reportCodeChange();
        }
      }
    }
  }


  /**
   * Rewrite type of the JSDoc annotations.
   * 
   * @author aono_taketoshi
   * 
   */
  private final class JSDocRewriter implements Rewriter {
    /**
     * The rewriter list.
     */
    private final ImmutableList<Rewriter> jsDocRewritePass = ImmutableList.<Rewriter> of(
        new AliasTypeRewriter(),
        new ExportedTypeRewriter(),
        new LocalTypeRewriter());


    @Override
    public void rewrite(ModuleInfo moduleInfo) {
      for (Rewriter rewriter : this.jsDocRewritePass) {
        rewriter.rewrite(moduleInfo);
      }
    }
  }


  /**
   * The base class of the JSDoc rewriters.
   * 
   * @author aono_taketoshi
   * 
   */
  private abstract class AbstractJSDocRewriter implements Rewriter {

    @Override
    public void rewrite(ModuleInfo moduleInfo) {
      List<JSDocMutator> jsDocMutatorList = this.getMutatorList(moduleInfo);
      for (JSDocMutator jsDocMutator : jsDocMutatorList) {
        String type = jsDocMutator.getTypeString();
        this.rewriteType(type, jsDocMutator, moduleInfo);
      }
    }


    /**
     * Rewrite the types of the JSDoc annotaions.
     * 
     * @param type
     *          The raw type.
     * @param mutator
     *          The JSDoc rewriter.
     * @param moduleInfo
     *          Current module information.
     */
    protected abstract void rewriteType(String type, JSDocMutator mutator, ModuleInfo moduleInfo);


    protected abstract List<JSDocMutator> getMutatorList(ModuleInfo moduleInfo);
  }


  private final class AliasTypeRewriter extends AbstractJSDocRewriter {

    @Override
    protected void rewriteType(String type, JSDocMutator mutator, ModuleInfo moduleInfo) {
      int index = type.indexOf(".");
      String prop = "";

      if (index != -1) {
        String top = type.substring(0, index);
        prop = type.substring(index);
        type = top;
      }

      String renamed = moduleInfo.getAliasName(type);
      if (renamed != null) {
        mutator.mutate(renamed + prop);
        if (mutator.isCodeChanged()) {
          compiler.reportCodeChange();
        }
      }
    }


    @Override
    protected List<JSDocMutator> getMutatorList(ModuleInfo moduleInfo) {
      return moduleInfo.getAliasTypeList();
    }
  }


  /**
   * Rewrite namespace referenced argument to current module full qualified name
   * in JSDoc annotations.
   * 
   * @author aono_taketoshi
   * 
   */
  private final class ExportedTypeRewriter extends AbstractJSDocRewriter {

    @Override
    protected void rewriteType(String type, JSDocMutator mutator, ModuleInfo moduleInfo) {
      Node nra = moduleInfo.getNamespaceReferenceArgument();
      String nraName = nra.getString();
      mutator.mutate(type.replaceFirst(nraName, moduleInfo.getModuleName()));
      if (mutator.isCodeChanged()) {
        compiler.reportCodeChange();
      }
    }


    @Override
    protected List<JSDocMutator> getMutatorList(ModuleInfo moduleInfo) {
      return moduleInfo.getExportedTypeList();
    }
  }


  /**
   * Rewrite the module local variable types to the global unique type name.
   * 
   * @author aono_taketoshi
   * 
   */
  private final class LocalTypeRewriter extends AbstractJSDocRewriter {

    @Override
    protected void rewriteType(String type, JSDocMutator mutator, ModuleInfo moduleInfo) {
      int index = type.indexOf(".");
      String prop = "";

      if (index != -1) {
        String top = type.substring(0, index);
        prop = type.substring(index);
        type = top;
      }

      String renamed = moduleInfo.getModuleId() + "_" + type;
      if (renamed != null) {
        mutator.mutate(renamed + prop);
        if (mutator.isCodeChanged()) {
          compiler.reportCodeChange();
        }
      }
    }


    @Override
    protected List<JSDocMutator> getMutatorList(ModuleInfo moduleInfo) {
      return moduleInfo.getLocalTypeList();
    }
  }


  /**
   * Rewrite 'camp.module' call to 'goog.provide' call.
   * 
   * @author aono_taketoshi
   * 
   */
  private final class ModuleRewriter implements Rewriter {

    @Override
    public void rewrite(ModuleInfo moduleInfo) {
      addGoogProvide(moduleInfo);
      Node moduleCall = moduleInfo.getModuleCallNode();
      Node closure = NodeUtil.getFunctionBody(moduleCall.getLastChild());
      closure.detachFromParent();
      moduleCall.getParent().getParent().replaceChild(moduleCall.getParent(), closure);
      compiler.reportCodeChange();
      NodeUtil.tryMergeBlock(closure);
    }


    /**
     * Append 'goog.provide' call to the head of the file.
     * 
     * @param moduleInfo
     */
    private void addGoogProvide(ModuleInfo moduleInfo) {
      for (String name : moduleInfo.getExportedList()) {
        Node provideName = NodeUtil.newQualifiedNameNode(convention, CampModuleConsts.GOOG_PROVIDE);
        Node provideCall = NodeUtil.newCallNode(provideName,
            Node.newString(name));
        Node expr = NodeUtil.newExpr(provideCall);
        expr.copyInformationFromForTree(moduleInfo.getModuleCallNode());
        Node block = NodeUtil.getFunctionBody(moduleInfo.getModuleCallNode().getLastChild());
        block.addChildToFront(expr);
        compiler.reportCodeChange();
      }
    }
  }
}
