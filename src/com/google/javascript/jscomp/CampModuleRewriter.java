package com.google.javascript.jscomp;

import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.javascript.jscomp.CampModuleTransformInfo.LocalAliasInfo;
import com.google.javascript.jscomp.CampModuleTransformInfo.ModuleInfo;
import com.google.javascript.jscomp.CampModuleTransformInfo.TypeInfo;
import com.google.javascript.jscomp.CampModuleTransformInfo.VarRenamePair;
import com.google.javascript.rhino.JSDocInfo;
import com.google.javascript.rhino.JSDocInfoBuilder;
import com.google.javascript.rhino.JSTypeExpression;
import com.google.javascript.rhino.Node;
import com.google.javascript.rhino.Token;

public class CampModuleRewriter {

  private final AbstractCompiler compiler;

  private final CodingConvention convention;

  private final CampModuleTransformInfo campModuleTransformInfo;

  private final RewritePassExecutor rewritePassExecutor = new RewritePassExecutor();


  public CampModuleRewriter(AbstractCompiler compiler,
      CampModuleTransformInfo campModuleTransformInfo) {
    this.compiler = compiler;
    this.convention = compiler.getCodingConvention();
    this.campModuleTransformInfo = campModuleTransformInfo;
  }


  public void process() {
    for (ModuleInfo moduleInfo : campModuleTransformInfo.getModuleInfoMap().values()) {
      this.rewritePassExecutor.execute(moduleInfo);
    }
  }


  private final class RewritePassExecutor {
    private final ImmutableList<Rewriter> rewritePass = new ImmutableList.Builder<Rewriter>()
        .add(new UsingCallRewriter())
        .add(new ExportsRewriter())
        .add(new VariableRewriter())
        .add(new JSDocRewriter())
        .add(new ModuleRewriter())
        .build();


    public void execute(ModuleInfo moduleInfo) {
      for (Rewriter rewriter : rewritePass) {
        rewriter.rewrite(moduleInfo);
      }
    }
  }


  private interface Rewriter {
    public void rewrite(ModuleInfo moduleInfo);
  }


  private final class UsingCallRewriter implements Rewriter {
    private Set<Node> replacedSet = Sets.newHashSet();


    @Override
    public void rewrite(ModuleInfo moduleInfo) {
      for (Node usingCall : moduleInfo.getUsingCallList()) {
        rewriteUsing(moduleInfo, usingCall);
      }

    }


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
          moduleInfo.putAliasMap(varName, qualifiedName);
        }
      }

      this.rewriteVars(moduleInfo, usingCall,
          NodeUtil.newQualifiedNameNode(convention, nameNode.getString()), parent);
      parent.detachFromParent();
      compiler.reportCodeChange();
    }


    private Node addGoogRequire(Node usingCall, Node parent) {
      Node nameNode = Node.newString(usingCall.getFirstChild().getNext().getString());
      Node requireCall = NodeUtil.newQualifiedNameNode(convention, CampModuleConsts.GOOG_REQUIRE);
      Node callNode = NodeUtil.newCallNode(requireCall, nameNode);
      Node expr = NodeUtil.newExpr(callNode);

      expr.copyInformationFromForTree(parent);
      parent.getParent().addChildAfter(expr, parent);
      return nameNode;
    }


    private void rewriteVars(ModuleInfo moduleInfo, Node usingCall, Node nameNode, Node parent) {
      usingCall.getParent().replaceChild(usingCall, nameNode);
      if (parent != null && parent.isVar()) {
        Node varNameNode = parent.getFirstChild();
        String varName = varNameNode.getString();
        List<Node> aliasVarList = moduleInfo.getAliasVarList();
        for (Node target : aliasVarList) {
          String name = target.getString();
          if (name.equals(varName) && !replacedSet.contains(target)) {
            this.replaceAlias(moduleInfo, nameNode, varNameNode, varName, target);
            replacedSet.add(target);
          }
        }
      }
    }


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


  private final class ExportsRewriter implements Rewriter {

    private final Set<String> fqnSet = Sets.newHashSet();

    private final LocalAliasResolver localAliasResolver = new LocalAliasResolver();


    private final class LocalAliasResolver {
      private Map<String, TypeInfo> inferedTypeInfoMap = Maps.newHashMap();


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
        moduleInfo.addLocalType(functionType.getFirstChild().getFirstChild());
        compiler.reportCodeChange();
      }


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
    }


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
      }
    }
  }


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


    private void rewriteVarName(ModuleInfo moduleInfo, Node declaration, Node target) {
      if (moduleInfo.isRenameVarBaseDeclaration(declaration)) {
        String after = moduleInfo.getRenamedVar(target.getString());
        if (after != null) {
          target.setString(after);
        }
      }
    }
  }


  private final class JSDocRewriter implements Rewriter {
    private final ImmutableList<Rewriter> jsDocRewritePass = new ImmutableList.Builder<Rewriter>()
        .add(new AliasTypeRewriter())
        .add(new ExportedTypeRewriter())
        .add(new LocalTypeRewriter())
        .build();


    @Override
    public void rewrite(ModuleInfo moduleInfo) {
      for (Rewriter rewriter : this.jsDocRewritePass) {
        rewriter.rewrite(moduleInfo);
      }
    }
  }


  private abstract class AbstractJSDocRewriter implements Rewriter {

    @Override
    public void rewrite(ModuleInfo moduleInfo) {
      Set<Node> targetTypeSet = this.getTypeSet(moduleInfo);
      for (Node typeNode : targetTypeSet) {
        String type = typeNode.getString();
        this.rewriteType(type, typeNode, moduleInfo);
      }
    }


    protected abstract void rewriteType(String type, Node typeNode, ModuleInfo moduleInfo);


    protected abstract Set<Node> getTypeSet(ModuleInfo moduleInfo);
  }


  private final class AliasTypeRewriter extends AbstractJSDocRewriter {

    @Override
    protected void rewriteType(String type, Node typeNode, ModuleInfo moduleInfo) {
      int index = type.indexOf(".");
      String prop = "";

      if (index != -1) {
        String top = type.substring(0, index);
        prop = type.substring(index);
        type = top;
      }

      String renamed = moduleInfo.getAliasName(type);
      if (renamed != null) {
        typeNode.setString(renamed + prop);
        compiler.reportCodeChange();
      }
    }


    @Override
    protected Set<Node> getTypeSet(ModuleInfo moduleInfo) {
      return moduleInfo.getAliasTypeSet();
    }
  }


  private final class ExportedTypeRewriter extends AbstractJSDocRewriter {

    @Override
    protected void rewriteType(String type, Node typeNode, ModuleInfo moduleInfo) {
      Node nra = moduleInfo.getNamespaceReferenceArgument();
      String nraName = nra.getString();
      typeNode.setString(type.replaceFirst(nraName, moduleInfo.getModuleName()));
      compiler.reportCodeChange();
    }


    @Override
    protected Set<Node> getTypeSet(ModuleInfo moduleInfo) {
      return moduleInfo.getExportedTypeSet();
    }
  }


  private final class LocalTypeRewriter extends AbstractJSDocRewriter {

    @Override
    protected void rewriteType(String type, Node typeNode, ModuleInfo moduleInfo) {
      typeNode.setString(moduleInfo.getModuleId() + "_" + type);
      compiler.reportCodeChange();
    }


    @Override
    protected Set<Node> getTypeSet(ModuleInfo moduleInfo) {
      return moduleInfo.getLocalTypeSet();
    }
  }


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
