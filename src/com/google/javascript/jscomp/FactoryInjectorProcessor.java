package com.google.javascript.jscomp;

import java.util.List;
import java.util.Set;

import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.google.javascript.jscomp.FactoryInjectorInfo.InjectInfo;
import com.google.javascript.jscomp.FactoryInjectorInfo.TypeInfo;
import com.google.javascript.rhino.IR;
import com.google.javascript.rhino.JSDocInfoBuilder;
import com.google.javascript.rhino.JSTypeExpression;
import com.google.javascript.rhino.Node;
import com.google.javascript.rhino.Token;

public class FactoryInjectorProcessor implements HotSwapCompilerPass {

  private static String FACTORY_NAME = "jscomp$newInstance";

  private static String INSTANCE_VAR = "jscomp$instanceVar";
  
  private static String BINDINGS = "bindings";
  
  private long instanceId = 0;

  private AbstractCompiler compiler;

  private FactoryInjectorInfo factoryInjectorInfo;

  private CodingConvention convention;

  private Set<TypeInfo> insertedTypeInfoSet = Sets.newHashSet();


  public FactoryInjectorProcessor(AbstractCompiler compiler) {
    this.compiler = compiler;
    this.convention = this.compiler.getCodingConvention();
    this.factoryInjectorInfo = new FactoryInjectorInfo();
  }


  @Override
  public void hotSwapScript(Node scriptRoot, Node originalRoot) {
    compiler.process(this);
  }


  @Override
  public void process(Node externsRoot, Node root) {
    new FactoryInjectorInfoCollector(compiler, this.factoryInjectorInfo).process(externsRoot, root);
    new Rewriter().rewrite();
  }


  static Node getStatementBeginningNode(Node n) {
    while (n != null) {
      switch (n.getType()) {
      case Token.EXPR_RESULT:
      case Token.VAR:
      case Token.CONST:
      case Token.THROW:
        return n;

      default:
        if (NodeUtil.isFunctionDeclaration(n)) {
          return n;
        }
        n = n.getParent();
      }
    }

    return null;
  }


  /**
   * Insert a static factory method.
   * 
   * @example <code>
   * function Foo(a,b,c) {
   * }
   * Foo.jscomp$newInstance = function(bindings) {
   *   return new Foo(bindings.getA(), bindings.getB(), bindings.getC());
   * }
   * </code>
   */
  private final class FactoryInjector {

    private TypeInfo constructorInfo;


    public FactoryInjector(TypeInfo typeInfo) {
      this.constructorInfo = typeInfo;
    }


    public void process() {
      if (insertedTypeInfoSet.contains(constructorInfo)) {
        return;
      }

      insertedTypeInfoSet.add(constructorInfo);

      // If constructor is already has factory method,
      // skip processes.
      if (constructorInfo.hasInstanceFactory()) {
        return;
      }

      constructorInfo.setHasInstanceFactory();

      Node constructorNode;

      constructorNode = constructorInfo.getConstructorNode();

      if (constructorNode != null) {
        insertFactory(constructorNode);
        compiler.reportCodeChange();
      }
    }


    /**
     * Insert a factory method to the node trees.
     */
    private void insertFactory(Node constructorNode) {
      Node stmtBeginning = getStatementBeginningNode(constructorNode);
      Preconditions.checkNotNull(stmtBeginning);

      if (stmtBeginning.getNext() != null) {
        Node next = stmtBeginning.getNext();
        while (true) {
          if (next.isCall() && next.getFirstChild().isGetProp()) {
            String qname = next.getFirstChild().getQualifiedName();
            if (qname != null && qname.equals("goog.inherits")) {
              stmtBeginning = stmtBeginning.getNext();
              break;
            }
          }

          next = next.getFirstChild();
          if (next == null) {
            break;
          }
        }
      }

      Node expr = null;
      String constructorName = constructorInfo.getName();

      if (!constructorInfo.isAlias()) {
        Node block = IR.block();
        Node assign = createFactoryAssignmentNode(constructorName, block);
        Node newCall = IR.newNode(NodeUtil.newQualifiedNameNode(convention, constructorName));

        List<String> paramList = Lists.newArrayList();
        for (Node paramNode : NodeUtil.getFunctionParameters(constructorInfo.getConstructorNode())
            .children()) {
          paramList.add(paramNode.getString());
        }
        
        block.addChildToBack(IR.returnNode(newCall));
        
        addParameters(newCall, paramList);
        addJSDocInfo(paramList, assign);

        NodeUtil.setDebugInformation(assign.getLastChild(), constructorNode, constructorName);
        expr = NodeUtil.newExpr(assign);
        expr.copyInformationFromForTree(stmtBeginning);

      } else {
        Node target = NodeUtil.newQualifiedNameNode(convention, constructorName + "."
            + FACTORY_NAME);
        Node alias = NodeUtil.newQualifiedNameNode(convention, constructorInfo.getAliasName() + "."
            + FACTORY_NAME);
        expr = NodeUtil.newExpr(IR.assign(target, alias));
      }

      if (stmtBeginning.getParent() == null) {
        stmtBeginning.addChildAfter(expr, stmtBeginning.getFirstChild());
      } else {
        stmtBeginning.getParent().addChildAfter(expr, stmtBeginning);
      }
    }


    /**
     * Create a factory method assignment node. e.g. <code>
     * function Foo(){}
     * Foo.jsomp$newInstance = function() {...
     * ---------------------^
     * </code>
     * 
     * @param constructorName
     *          The qualified name of the constructor function.
     * @param block
     *          The factory method body node.
     * @return An assignment node.
     */
    private Node createFactoryAssignmentNode(String constructorName, Node block) {
      String factoryName = createFactoryMethodName(constructorName);
      Node factoryNameNode =
          NodeUtil.newQualifiedNameNode(convention, factoryName);

      Node paramNameNode = IR.name(BINDINGS);
      Node functionNode =
          IR.function(IR.name(""), IR.paramList(paramNameNode), block);
      Node assign = IR.assign(factoryNameNode, functionNode);

      return assign;
    }


    private void addJSDocInfo(List<String> paramList, Node assign) {
      if (paramList.size() > 0) {
        JSDocInfoBuilder builder = new JSDocInfoBuilder(false);
        String sourceFileName = assign.getSourceFileName();
        Node name = IR.string(constructorInfo.getName());
        JSTypeExpression returnType = new JSTypeExpression(name, sourceFileName);
        builder.recordReturnType(returnType);
        assign.setJSDocInfo(builder.build(assign));
      }
    }


    /**
     * Make the factory method name.
     * 
     * @param constructorName
     *          The qualified name of the constructor function.
     * @return The factory method name.
     */
    private String createFactoryMethodName(String constructorName) {
      return constructorName + "." + FACTORY_NAME;
    }


    /**
     * Append parameter nodes to a call node.
     * 
     * @param callNode
     *          A function call node.
     * @param paramList
     *          The list of parameter names.
     */
    private void addParameters(Node callNode, List<String> paramList) {
      for (String param : paramList) {
        Node call = getBindingMethodCallNode(param);
        callNode.addChildToBack(call);
      }
    }


    private Node getBindingMethodCallNode(String name) {
      Node getprop = NodeUtil.newQualifiedNameNode(convention, BINDINGS + "." + name);
      return NodeUtil.newCallNode(getprop);
    }
  }


  private final class Rewriter {
    public void rewrite() {
      rewriteResolveCalls();
    }


    private void rewriteResolveCalls() {
      for (InjectInfo injectInfo : factoryInjectorInfo.getInjectInfoList()) {
        createInlineBindingInjector(injectInfo);
      }
    }


    private void injectFactory(List<TypeInfo> typeInfoList) {
      for (TypeInfo typeInfo : typeInfoList) {
        new FactoryInjector(typeInfo).process();
      }
    }


    private void createInlineBindingInjector(InjectInfo injectInfo) {
      Node injectCall = injectInfo.getNode();
      Node target = injectCall.getFirstChild().getNext();
      Node inject = target.getNext();
      String name = target.getQualifiedName();
      if (name != null) {
        List<TypeInfo> typeInfoList = factoryInjectorInfo.getTypeInfoMap().get(name);
        if (typeInfoList.size() > 0) {
          injectFactory(typeInfoList);
          Node getprop = NodeUtil.newQualifiedNameNode(convention, name + "." + FACTORY_NAME);
          Node call = NodeUtil.newCallNode(getprop, inject.cloneTree());
          if (injectInfo.isInjectOnce()) {
            Node instanceVar = IR.getprop(inject.cloneTree(), IR.string(INSTANCE_VAR + instanceId));
            instanceId++;
            Node assign = IR.assign(instanceVar.cloneTree(), call);
            Node or = IR.or(instanceVar, assign);
            call = or;
          }
          call.copyInformationFromForTree(injectCall);
          injectCall.getParent().replaceChild(injectCall, call);
        }
      }
    }
  }
}
