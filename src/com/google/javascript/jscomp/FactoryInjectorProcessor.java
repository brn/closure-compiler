package com.google.javascript.jscomp;

import java.util.List;

import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import com.google.javascript.jscomp.FactoryInjectorInfo.CallType;
import com.google.javascript.jscomp.FactoryInjectorInfo.MethodInjectionInfo;
import com.google.javascript.jscomp.FactoryInjectorInfo.ResolvePoints;
import com.google.javascript.jscomp.FactoryInjectorInfo.TypeInfo;
import com.google.javascript.rhino.IR;
import com.google.javascript.rhino.Node;
import com.google.javascript.rhino.Token;

public class FactoryInjectorProcessor implements HotSwapCompilerPass {

  private static String FACTORY_NAME = "jscomp$newInstance";

  private static String ONCE_VAR = "jscomp$instanceVar";
  
  private int singletonId = 0;

  private AbstractCompiler compiler;

  private FactoryInjectorInfo factoryInjectorInfo;

  private CodingConvention convention;


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

    private static final String INSTANCE_NAME = "instance";

    private TypeInfo constructorInfo;


    public FactoryInjector(TypeInfo typeInfo) {
      this.constructorInfo = typeInfo;
    }


    public void process() {

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
        addParameters(newCall, paramList);
        addMethodInjections(block, newCall);

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

      Node paramNameNode = IR.name(DIConsts.BINDINGS_REPO_NAME);
      Node functionNode =
          IR.function(IR.name(""), IR.paramList(paramNameNode), block);
      Node assign = IR.assign(factoryNameNode, functionNode);
      return assign;
    }


    /**
     * Make the factory method name.
     * 
     * @param constructorName
     *          The qualified name of the constructor function.
     * @return The factory method name.
     */
    private String createFactoryMethodName(String constructorName) {
      return constructorName + "." + DIConsts.FACTORY_METHOD_NAME;
    }


    /**
     * Insert method injection calls to the factory method body. This method
     * generate the or-expression here because the compiler can not determine
     * which method is has been the subject of dependency injection.
     * 
     * @param block
     *          The factory method body.
     * @param newCall
     *          The new call expression.
     */
    private void addMethodInjections(Node block, Node newCall) {
      List<MethodInjectionInfo> methodInjectionInfoList =
          constructorInfo.getMethodInjectionList();

      // If the method injection target is not specified
      // create a simple return statement.
      if (methodInjectionInfoList.size() > 0) {
        Node instance = IR.name(INSTANCE_NAME);
        block.addChildToBack(NodeUtil.newVarNode(INSTANCE_NAME, newCall));
        addMethodCalls(block, methodInjectionInfoList);
        block.addChildToBack(IR.returnNode(instance));
      } else {
        block.addChildToBack(IR.returnNode(newCall));
      }
    }


    /**
     * Create and-expression including a method calling.
     * 
     * @param block
     *          The factory method body.
     * @param methodInjectionInfoList
     *          The list of the target method name.
     */
    private void addMethodCalls(Node block, List<MethodInjectionInfo> methodInjectionInfoList) {
      for (MethodInjectionInfo methodInjectionInfo : methodInjectionInfoList) {
        String methodName = methodInjectionInfo.getMethodName();
        Node getprop = NodeUtil.newQualifiedNameNode(convention, INSTANCE_NAME + "." + methodName);
        Node methodCall = NodeUtil.newCallNode(getprop);
        addParameters(methodCall, methodInjectionInfo.getParameterList());
        Node and = IR.and(getprop.cloneTree(), methodCall);
        block.addChildToBack(IR.exprResult(and));
      }
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

        // If a parameter name is ends with "Provider",
        // the compiler create code which pass the factory method itself
        // directly.
        // e.g.
        // function Foo(barProvider){}
        // Foo.jscomp$newInstance = function(bindings) {
        // return new Foo(bindings.getBar);
        // }
        String paramName;
        if (param.endsWith("Provider")) {
          paramName = param.replaceFirst("Provider", "");
          Node getprop = getBindingGetterMethodName(paramName);
          Node call = NodeUtil.newCallNode(getprop);
          callNode.addChildToBack(IR.function(IR.name(""), IR.paramList(), IR.block(IR.returnNode(call))));
        } else {
          paramName = param;
          Node getprop = getBindingGetterMethodName(param);
          Node call = NodeUtil.newCallNode(getprop);
          callNode.addChildToBack(call);
        }
      }
    }


    private Node getBindingGetterMethodName(String name) {
      return NodeUtil.newQualifiedNameNode(convention, DIConsts.BINDINGS_REPO_NAME + "."
          + DIProcessor.toGetter(name));
    }
  }


  private final class Rewriter {
    public void rewrite() {
      rewriteResolveCalls();
    }


    private void rewriteResolveCalls() {
      for (ResolvePoints resolvePoints : factoryInjectorInfo.getResolvePointsList()) {
        Node factoryCall = createFactoryCall(resolvePoints);
        if (factoryCall != null) {
          Node call = resolvePoints.getResolveCallNode();
          factoryCall.copyInformationFromForTree(call);
          call.getParent().replaceChild(call, factoryCall);
        }
      }
    }


    private void injectFactory(List<TypeInfo> typeInfoList) {
      for (TypeInfo typeInfo : typeInfoList) {
        new FactoryInjector(typeInfo).process();
      }
    }


    private Node createFactoryCall(ResolvePoints resolvePoints) {
      CallType callType = resolvePoints.getCallType();
      String name = resolvePoints.getTypeName();
      Node target = resolvePoints.getFromNode();
      List<TypeInfo> typeInfoList = factoryInjectorInfo.getTypeInfoMap().get(name);
      if (typeInfoList.size() > 0) {
        injectFactory(typeInfoList);
        Node getprop = NodeUtil.newQualifiedNameNode(convention, name + "." + FACTORY_NAME);
        Node call = NodeUtil.newCallNode(getprop, target.cloneTree());

        switch (callType) {
        case RESOLVE:
          return call;
        case RESOLVE_ONCE:
          Node instanceVar = IR.getprop(target.cloneTree(), IR.string(ONCE_VAR + singletonId++));
          return IR.or(instanceVar, IR.assign(instanceVar.cloneTree(), call));
        }
      }
      return null;
    }

  }
}
