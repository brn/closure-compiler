package com.google.javascript.jscomp;

import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.javascript.jscomp.FactoryInjectorInfo.BinderInfo;
import com.google.javascript.jscomp.FactoryInjectorInfo.NewWithInfo;
import com.google.javascript.jscomp.FactoryInjectorInfo.TypeInfo;
import com.google.javascript.rhino.IR;
import com.google.javascript.rhino.JSDocInfo;
import com.google.javascript.rhino.JSDocInfoBuilder;
import com.google.javascript.rhino.JSTypeExpression;
import com.google.javascript.rhino.Node;
import com.google.javascript.rhino.Token;

public class FactoryInjectorProcessor implements HotSwapCompilerPass {

  private static String FACTORY_NAME = "jscomp$newInstance";

  private static String INSTANCE_VAR = "jscomp$instanceVar";
  
  private static String BINDINGS = "bindings";

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
        JSDocInfo info = constructorInfo.getJSDocInfo();
        JSDocInfoBuilder builder = new JSDocInfoBuilder(false);
        Map<String, Node> typeMap = Maps.newHashMap();
        for (String param : paramList) {
          typeMap.put(param, null);
        }
        if (info != null) {
          for (String paramName : info.getParameterNames()) {
            JSTypeExpression paramType = info.getParameterType(paramName);
            if (paramType != null) {
              if (typeMap.containsKey(paramName)) {
                typeMap.put(paramName, paramType.getRoot().cloneTree());
              }
            }
          }
        }
        
        Node lb = new Node(Token.LB);
        Node obj = new Node(Token.LC, lb);
        for (String paramName : paramList) {
          Node key = IR.string(paramName);
          if (typeMap.get(key) != null) {
            lb.addChildToBack(new Node(Token.COLON, key, typeMap.get(key)));
          } else {
            lb.addChildToBack(key);
          }
        }
        String sourceFileName = assign.getSourceFileName();
        JSTypeExpression recordType = new JSTypeExpression(obj, sourceFileName);
        Node name = IR.string(constructorInfo.getName());
        JSTypeExpression returnType = new JSTypeExpression(name, sourceFileName);
        builder.recordParameter(BINDINGS, recordType);
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
      return constructorName + "." + DIConsts.FACTORY_METHOD_NAME;
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
        Node getprop = getBindingName(param);
        callNode.addChildToBack(getprop);
      }
    }


    private Node getBindingName(String name) {
      return NodeUtil.newQualifiedNameNode(convention, DIConsts.BINDINGS_REPO_NAME + "." + name);
    }
  }


  private final class Rewriter {
    public void rewrite() {
      rewriteResolveCalls();
    }


    private void rewriteResolveCalls() {
      for (NewWithInfo newWithInfo : factoryInjectorInfo.getNewWithInfoList()) {
        createInlineBindingInjector(newWithInfo);
      }

      for (BinderInfo binderInfo : factoryInjectorInfo.getBinderInfoList()) {
        createBindingInjector(binderInfo);
      }
    }


    private void injectFactory(List<TypeInfo> typeInfoList) {
      for (TypeInfo typeInfo : typeInfoList) {
        new FactoryInjector(typeInfo).process();
      }
    }


    private void createInlineBindingInjector(NewWithInfo newWithInfo) {
      Node newWithCall = newWithInfo.getNode();
      Node target = newWithCall.getFirstChild().getNext();
      Node inject = target.getNext();
      String name = target.getQualifiedName();
      if (name != null) {
        List<TypeInfo> typeInfoList = factoryInjectorInfo.getTypeInfoMap().get(name);
        if (typeInfoList.size() > 0) {
          injectFactory(typeInfoList);
          Node getprop = NodeUtil.newQualifiedNameNode(convention, name + "." + FACTORY_NAME);
          Node call = NodeUtil.newCallNode(getprop, NodeUtil.newCallNode(inject.cloneTree()));
          call.copyInformationFromForTree(newWithCall);
          newWithCall.getParent().replaceChild(newWithCall, call);
        }
      }
    }


    private void createBindingInjector(BinderInfo binderInfo) {
      Node bindCall = binderInfo.getNode();
      Node binding = bindCall.getFirstChild().getNext();
      Node objLit = binding.getNext();
      Node ret = IR.objectlit();

      for (Node keyNode : objLit.children()) {
        String key = keyNode.getString();
        Node value = keyNode.getFirstChild();
        String name = value.getQualifiedName();
        List<TypeInfo> typeInfoList = factoryInjectorInfo.getTypeInfoMap().get(name);
        if (typeInfoList.size() == 0) {
          report(value, FactoryInjectorInfoCollector.MESSAGE_BIND_OBJECT_LITERAL_MEMBER_IS_INVALID);
          continue;
        }
        injectFactory(typeInfoList);
        Node getprop = NodeUtil.newQualifiedNameNode(convention, name + "." + FACTORY_NAME);
        Node target = IR.getprop(binding.cloneTree(), IR.string(key));
        Node call = NodeUtil.newCallNode(getprop, NodeUtil.newCallNode(target));
        if (binderInfo.isSingleton()) {
          Node instanceVar = IR.getprop(target.cloneTree(), IR.string(INSTANCE_VAR));
          call = IR.or(instanceVar, IR.assign(instanceVar.cloneTree(), call));
        }
        Node fn = buildFunctionExpression(call);
        Node newKeyNode = IR.stringKey(key);
        createReturnJSDocInfo(name, keyNode, newKeyNode);
        newKeyNode.copyInformationFromForTree(keyNode);
        fn.copyInformationFromForTree(value);
        newKeyNode.addChildToBack(fn);
        ret.addChildToBack(newKeyNode);
      }
      ret.copyInformationFromForTree(objLit);
      bindCall.getParent().replaceChild(bindCall, ret);
    }


    private Node buildFunctionExpression(Node call) {
      return IR.function(IR.name(""), IR.paramList(),
          IR.block(IR.returnNode(call)));
    }


    private void createReturnJSDocInfo(String name, Node keyNode, Node newKeyNode) {
      Node retType = IR.string(name);
      JSDocInfoBuilder builder = new JSDocInfoBuilder(false);
      builder.recordReturnType(new JSTypeExpression(retType, keyNode.getSourceFileName()));
      newKeyNode.setJSDocInfo(builder.build(newKeyNode));
    }
  }


  private void report(Node n, DiagnosticType message, String... arguments) {
    JSError error = JSError.make(n.getSourceFileName(),
        n, message, arguments);
    compiler.report(error);
  }
}
