package com.google.javascript.jscomp;

import com.google.common.base.Preconditions;
import com.google.javascript.rhino.Node;
import com.google.javascript.rhino.Token;

/**
 * Support dependency injection and aspect oriented programming.
 * 
 * @author brn
 */
public class CampInjectionProcessor implements HotSwapCompilerPass {

  private AbstractCompiler compiler;
  
  @Override
  public void process(Node extern, Node root) {
    CampInjectionInfo  campInjectionInfo = new CampInjectionInfo();
    new CampInjectionInfoCollector(compiler, campInjectionInfo).collectInfo(root);
    new CampInjectionRewriter(compiler, campInjectionInfo).rewrite();
  }

  @Override
  public void hotSwapScript(Node scriptRoot, Node originalRoot) {
    this.compiler.process(this);

  }

  public CampInjectionProcessor(AbstractCompiler compiler) {
    this.compiler = compiler;
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
  
  static Node newQualifiedNameNode(String name) {
    String[] moduleNames = name.split("\\.");
    Node prop = null;
    for (String moduleName : moduleNames) {
      if (prop == null) {
        prop = newNameNodeOrKeyWord(moduleName, true);
      } else {
        prop = new Node(Token.GETPROP, prop, newNameNodeOrKeyWord(moduleName, false));
      }
    }
    return prop;
  } 
  
   static Node newNameNodeOrKeyWord(String name, boolean isFirst) {
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
