package com.google.javascript.jscomp;

import java.util.List;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;

public class CampPassConfig extends DefaultPassConfig {

  private boolean inserted = false;

  private boolean mixinInserted = false;

  private CompilerOptions options;

  ImmutableList<HotSwapPassFactory> SPECIAL_PASSES = new ImmutableList.Builder<HotSwapPassFactory>()
      .add(
          new HotSwapPassFactory("campModuleProcessor", true) {
            @Override
            protected HotSwapCompilerPass create(AbstractCompiler compiler) {
              return new CampModuleProcessor(compiler);
            }
          })
      .add(
          new HotSwapPassFactory("campFactoryInjector", true) {
            @Override
            protected HotSwapCompilerPass create(AbstractCompiler compiler) {
              return new FactoryInjectorProcessor(compiler);
            }
          })
      .add(
          new HotSwapPassFactory("mixinProcessor", true) {
            @Override
            protected HotSwapCompilerPass create(AbstractCompiler compiler) {
              return new MixinProcessor(compiler);
            }
          }
      )
      .build();


  public CampPassConfig(CompilerOptions option) {
    super(option);
    this.options = option;
  }


  @Override
  protected List<PassFactory> getChecks() {
    List<PassFactory> ret = super.getChecks();
    List<PassFactory> specialPass = Lists.newArrayList();

    for (PassFactory passFactory : ret) {
      specialPass.add(passFactory);
      if (passFactory.equals(closureRewriteGoogClass) && !inserted) {
        inserted = true;
        specialPass.addAll(SPECIAL_PASSES);
      } else if (passFactory.equals(checkSideEffects) && !inserted) {
        inserted = true;
        specialPass.addAll(SPECIAL_PASSES);
      }
    }
    return specialPass;
  }
}
