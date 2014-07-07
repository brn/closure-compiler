package com.google.javascript.jscomp;

import com.google.javascript.rhino.Node;
import com.google.javascript.rhino.Token;

/**
 * The utility class for the compiler and the node.
 * 
 * @author aono_taketoshi
 * 
 */
public class CampUtil {

  private static AbstractCompiler compiler;


  /**
   * Return the statement beggining parent node.
   * 
   * @param n
   *          The node that want to get statement parent node.
   * @return The node or null.
   */
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
   * Report error.
   * 
   * @param n
   *          The error occurrence node.
   * @param diagnosticType
   *          Error message.
   * @param arguments
   *          Error message arguments.
   */
  static void report(Node n, DiagnosticType diagnosticType,
      String... arguments) {
    JSError error = JSError.make(
        n.getSourceFileName(), n, diagnosticType, arguments);
    compiler.report(error);
  }


  /**
   * Set the compiler.
   * 
   * @param compiler
   *          The compiler
   */
  static void setCompiler(AbstractCompiler compiler) {
    CampUtil.compiler = compiler;
  }


  /**
   * Return the compiler.
   * 
   * @return The compiler.
   */
  static AbstractCompiler getCompiler() {
    return compiler;
  }


  /**
   * Report code has changed.
   */
  static void reportCodeChange() {
    compiler.reportCodeChange();
  }
}
