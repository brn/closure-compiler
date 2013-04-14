package com.google.javascript.jscomp;

import java.util.List;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;

public class CampPassConfig extends DefaultPassConfig {
  
  private boolean inserted = false;
  
  ImmutableList<HotSwapPassFactory> SPECIAL_PASSES = new ImmutableList.Builder<HotSwapPassFactory>()
      .add(
          new HotSwapPassFactory("campModuleProcessor", true) {
            @Override
            protected HotSwapCompilerPass create(AbstractCompiler compiler) {
              return new CampModuleProcessor(compiler);
            }
          })
      .add(
          new HotSwapPassFactory("campInjectionProcessor", true) {
            @Override
            protected HotSwapCompilerPass create(AbstractCompiler compiler) {
              return new DIProcessor(compiler);
            }
          })
      .build();


  public CampPassConfig(CompilerOptions option) {
    super(option);
  }


  @Override
  protected List<PassFactory> getChecks() {
    List<PassFactory> ret = super.getChecks();
    List<PassFactory> specialPass = Lists.newArrayList();
    for (PassFactory passFactory : ret) {
      if (passFactory.equals(closureRewriteGoogClass) && !inserted) {
        inserted = true;
        specialPass.addAll(SPECIAL_PASSES);
      } else if (passFactory.equals(checkSideEffects) && !inserted) {
        inserted = true;
        specialPass.addAll(SPECIAL_PASSES);
      }
      specialPass.add(passFactory);
    }
    return specialPass;
  }
}
