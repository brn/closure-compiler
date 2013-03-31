package com.google.javascript.jscomp;

import com.google.common.base.Preconditions;
import com.google.javascript.rhino.Node;
import com.google.javascript.rhino.Token;

/**
 * Support dependency injection and aspect oriented programming.
 * 
 * @author brn
 */
public class CampInjectionProcessor {

  public void process(Node extern, Node root) {
    CampInjectionInfoCollector campInjectionInfoCollector = new CampInjectionInfoCollector(compiler);
    campInjectionInfoCollector.collectInfo(root);
    campInjectionInfoCollector.integrateInfo();
    new CampInjectionRewriter(compiler).rewrite();
  }


  public CampInjectionProcessor(AbstractCompiler compiler) {
    this.compiler = compiler;
  }

  AbstractCompiler compiler;


  static Node createQualifiedNameNode(String name) {
    String[] moduleNames = name.split("\\.");
    Node prop = null;
    for (String moduleName : moduleNames) {
      if (prop == null) {
        prop = makeNameNodeOrKeyWord(moduleName, true);
      } else {
        prop = new Node(Token.GETPROP, prop, makeNameNodeOrKeyWord(moduleName, false));
      }
    }
    return prop;
  }


  static Node getFunctionParameter(Node function) {
    return NodeUtil.getFunctionParameters(function);
  }


  static Node getFunctionBody(Node function) {
    return NodeUtil.getFunctionBody(function);
  }


  static String toLowerCase(String className) {
    char a = className.charAt(0);
    return Character.toLowerCase(a) + className.substring(1);
  }


  static String getValidVarName(String className) {
    return className.replaceAll("\\.", "_");
  }


  static void detachStatement(Node n) {
    n = getStatementParent(n);
    if (n != null) {
      n.detachFromParent();
    }
  }


  static Node getStatementParent(Node n) {
    while (n != null && !NodeUtil.isStatement(n)) {
      n = n.getParent();
    }
    return n;
  }


  static void replaceNode(Node child, Node newChild) {
    Preconditions.checkNotNull(child);
    Preconditions.checkNotNull(newChild);
    child.getParent().replaceChild(child, newChild);
  }


  static Node makeVar(Node nameNode) {
    Preconditions.checkNotNull(nameNode);
    return new Node(Token.VAR, nameNode);
  }


  static Node makeVar(String name) {
    return makeVar(Node.newString(Token.NAME, name));
  }


  static Node makeVar(Node nameNode, Node initialValue) {
    nameNode.addChildToBack(initialValue);
    return makeVar(nameNode);
  }


  static Node makeVar(String name, Node initialValue) {
    Node nameNode = Node.newString(Token.NAME, name);
    nameNode.addChildToBack(initialValue);
    return makeVar(nameNode);
  }


  static Node makeUndefined() {
    return new Node(Token.CALL, new Node(Token.VOID), Node.newNumber(0));
  }


  static Node makeNameNodeOrKeyWord(String name, boolean isFirst) {
    if (name.equals("this")) {
      return new Node(Token.THIS);
    } else {
      if (isFirst) {
        return Node.newString(Token.NAME, name);
      } else {
        return Node.newString(name);
      }
    }
  }
}
