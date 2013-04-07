package com.google.javascript.jscomp;

import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.common.base.Strings;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.google.javascript.jscomp.CampModuleTransformInfo.ModuleInfo;
import com.google.javascript.jscomp.CampModuleTransformInfo.VarRenamePair;
import com.google.javascript.jscomp.Scope.Var;
import com.google.javascript.rhino.Node;

public class CampModuleRewriter {

  private final AbstractCompiler compiler;

  private final CodingConvention convention;

  private CampModuleTransformInfo campModuleTransformInfo;

  private final ExportsRewriter exportsRewriter = new ExportsRewriter();

  private final UsingCallRewriter usingCallRewriter = new UsingCallRewriter();

  private final VariableRewriter variableRewriter = new VariableRewriter();

  private final ModuleRewriter moduleRewriter = new ModuleRewriter();

  private final JSDocRewriter jsDocRewriter = new JSDocRewriter();


  public CampModuleRewriter(AbstractCompiler compiler,
      CampModuleTransformInfo campModuleTransformInfo) {
    this.compiler = compiler;
    this.convention = compiler.getCodingConvention();
    this.campModuleTransformInfo = campModuleTransformInfo;
  }


  public void process() {
    for (ModuleInfo moduleInfo : campModuleTransformInfo.getModuleInfoMap().values()) {
      usingCallRewriter.rewrite(moduleInfo);
      exportsRewriter.rewrite(moduleInfo);
      variableRewriter.rewrite(moduleInfo);
      jsDocRewriter.rewrite(moduleInfo);
      moduleRewriter.rewrite(moduleInfo);
    }
  }


  private interface Rewriter {
    public void rewrite(ModuleInfo moduleInfo);
  }


  private final class UsingCallRewriter implements Rewriter {

    public void rewrite(ModuleInfo moduleInfo) {
      List<Node> usingCallList = moduleInfo.getUsingCallList();

      for (Node usingCall : usingCallList) {

        Node parent = usingCall.getParent();
        while (parent != null && (!parent.isExprResult() && !parent.isVar())) {
          parent = parent.getParent();
        }

        Node nameNode = Node.newString(usingCall.getFirstChild().getNext().getString());
        Node requireCall = NodeUtil.newQualifiedNameNode(convention, CampModuleConsts.GOOG_REQUIRE);
        Node callNode = NodeUtil.newCallNode(requireCall, nameNode);
        Node expr = NodeUtil.newExpr(callNode);
        expr.copyInformationFromForTree(parent);
        parent.getParent().addChildAfter(expr, parent);
        this.rewriteVars(moduleInfo, usingCall,
            NodeUtil.newQualifiedNameNode(convention, nameNode.getString()), parent);
        parent.detachFromParent();
        compiler.reportCodeChange();

      }

    }


    private void rewriteVars(ModuleInfo moduleInfo, Node usingCall, Node nameNode, Node parent) {
      usingCall.getParent().replaceChild(usingCall, nameNode);
      if (parent != null && parent.isVar()) {
        Node varNameNode = parent.getFirstChild();
        String varName = varNameNode.getString();
        List<Node> aliasVarList = moduleInfo.getAliasVarList();
        for (Node target : aliasVarList) {
          String name = target.getString();
          if (name.equals(varName)) {
            String qualifiedName = nameNode.getQualifiedName();
            if (moduleInfo.getAliasName(varName) == null) {
              moduleInfo.putAliasMap(varName, qualifiedName);
            }
            Node replaced = varNameNode.getFirstChild();
            replaced.detachFromParent();
            replaced.copyInformationFromForTree(target);
            target.getParent().replaceChild(target, replaced);
            compiler.reportCodeChange();
            break;
          }
        }
      }
    }
  }


  private final class ExportsRewriter implements Rewriter {

    private Set<String> fqnSet = Sets.newHashSet();


    public void rewrite(ModuleInfo moduleInfo) {
      String moduleName = moduleInfo.getModuleName();
      Set<Node> exportsSet = moduleInfo.getExportsSet();
      for (Node exports : exportsSet) {
        String qualifiedName = exports.getQualifiedName();
        String[] splited = qualifiedName.split("\\.");
        String name = moduleName;

        if (splited.length > 2) {
          for (int i = 1; i < splited.length; i++) {
            name += "." + splited[i];
          }
        } else if (splited.length == 2) {
          name += ("." + splited[1]);
        }

        if (!fqnSet.contains(name) &&
            splited.length == 2 &&
            !splited[1].equals("main")) {
          addGoogProvide(moduleInfo, exports, name);
        }

        Node fqn = NodeUtil.newQualifiedNameNode(convention, name);
        fqn.copyInformationFromForTree(exports);
        exports.getParent().replaceChild(exports, fqn);
      }

      Node main = moduleInfo.getMain();

      if (main != null) {
        Node assign = main.getParent();
        main.detachFromParent();
        Node call = NodeUtil.newCallNode(main);
        assign.getParent().replaceChild(assign, call);
        Node expr = call.getParent();
        expr.detachFromParent();
        moduleInfo.getModuleCallNode().getLastChild().getLastChild().addChildToBack(expr);
      }

      compiler.reportCodeChange();
    }


    private void addGoogProvide(ModuleInfo moduleInfo, Node exports, String name) {
      fqnSet.add(name);
      Node provideName = NodeUtil.newQualifiedNameNode(convention, CampModuleConsts.GOOG_PROVIDE);
      Node provideCall = NodeUtil.newCallNode(provideName,
          Node.newString(name));
      Node expr = NodeUtil.newExpr(provideCall);
      expr.copyInformationFromForTree(exports);
      Node block = NodeUtil.getFunctionBody(moduleInfo.getModuleCallNode().getLastChild());
      block.addChildToFront(expr);
      compiler.reportCodeChange();
    }
  }


  private final class VariableRewriter implements Rewriter {
    public void rewrite(ModuleInfo moduleInfo) {
      List<VarRenamePair> variableList = moduleInfo.getRenameTargetList();
      for (VarRenamePair varRenamePair : variableList) {
        Node declaration = varRenamePair.getBaseDeclaration();
        Node target = varRenamePair.getTargetNode();
        if (moduleInfo.isRenameVarBaseDeclaration(declaration)) {
          String after = moduleInfo.getRenamedVar(target.getString());
          if (after != null) {
            target.setString(after);
          }
        }
      }
    }
  }


  private final class JSDocRewriter implements Rewriter {
    public void rewrite(ModuleInfo moduleInfo) {
      this.rewriteAliasType(moduleInfo);
      this.rewriteExportedType(moduleInfo);
      this.rewriteLocalType(moduleInfo);
      compiler.reportCodeChange();
    }


    private void rewriteAliasType(ModuleInfo moduleInfo) {
      Set<Node> typeNodeSet = moduleInfo.getAliasTypeSet();
      for (Node typeNode : typeNodeSet) {
        String type = typeNode.getString();
        String renamed = moduleInfo.getAliasName(type);
        if (renamed != null) {
          typeNode.setString(renamed);
        }
      }
    }


    private void rewriteExportedType(ModuleInfo moduleInfo) {
      Set<Node> typeNodeSet = moduleInfo.getExportedTypeSet();
      for (Node typeNode : typeNodeSet) {
        String type = typeNode.getString();
        Node nra = moduleInfo.getNamespaceReferenceArgument();
        String nraName = nra.getString();
        typeNode.setString(type.replaceFirst(nraName, moduleInfo.getModuleName()));
      }
    }


    private void rewriteLocalType(ModuleInfo moduleInfo) {
      Set<Node> typeNodeSet = moduleInfo.getLocalTypeSet();
      for (Node typeNode : typeNodeSet) {
        String type = typeNode.getString();
        typeNode.setString(moduleInfo.getModuleId() + "_" + type);
      }
    }
  }


  private final class ModuleRewriter implements Rewriter {
    public void rewrite(ModuleInfo moduleInfo) {
      Node moduleCall = moduleInfo.getModuleCallNode();
      Node closure = NodeUtil.getFunctionBody(moduleCall.getLastChild());
      closure.detachFromParent();
      moduleCall.getParent().getParent().replaceChild(moduleCall.getParent(), closure);
      compiler.reportCodeChange();
      NodeUtil.tryMergeBlock(closure);
    }
  }
}
