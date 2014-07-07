package com.google.javascript.jscomp;

import com.google.javascript.jscomp.AbstractCompiler;
import com.google.javascript.rhino.Node;

/**
 * Transform the camp style module codes to google closure style module codes.
 * 
 * @author aono_taketoshi
 * 
 */
public final class CampModuleProcessor implements HotSwapCompilerPass {

  private AbstractCompiler compiler;


  public CampModuleProcessor(AbstractCompiler compiler) {
    this.compiler = compiler;
  }


  @Override
  public void hotSwapScript(Node scriptRoot, Node originalRoot) {
    this.compiler.process(this);
  }


  @Override
  public void process(Node externs, Node root) {
    CampModuleTransformInfo campModuleTransformInfo = new CampModuleTransformInfo();
    new CampModuleInfoCollector(compiler, campModuleTransformInfo).process(root);
    new CampModuleRewriter(compiler, campModuleTransformInfo).process();
  }

}
