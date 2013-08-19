package com.google.javascript.jscomp;

import com.google.javascript.rhino.Node;
import com.google.javascript.rhino.Token;

public class CampUtil {

  private static AbstractCompiler compiler;


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


  static void report(Node n, DiagnosticType diagnosticType,
      String... arguments) {
    JSError error = JSError.make(
        n.getSourceFileName(), n, diagnosticType, arguments);
    compiler.report(error);
  }


  static void setCompiler(AbstractCompiler compiler) {
    CampUtil.compiler = compiler;
  }
  
  static AbstractCompiler getCompiler() {
    return compiler;
  }
  
  static void reportCodeChange() {
    compiler.reportCodeChange();
  }
}
