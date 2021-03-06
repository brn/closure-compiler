package com.google.javascript.jscomp;

import java.util.List;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;

/**
 * Special passes for the camp style module and the dependency injection.
 * 
 * @author aono_taketoshi
 * 
 */
public class CampPassConfig extends DefaultPassConfig {

  private boolean inserted = false;

  @SuppressWarnings("unused")
  private CompilerOptions options;

  ImmutableList<HotSwapPassFactory> SPECIAL_PASSES = ImmutableList.of(
      new HotSwapPassFactory("campModuleProcessor", true) {
        @Override
        protected HotSwapCompilerPass create(AbstractCompiler compiler) {
          return new CampModuleProcessor(compiler);
        }
      },

      new HotSwapPassFactory("campFactoryInjector", true) {
        @Override
        protected HotSwapCompilerPass create(AbstractCompiler compiler) {
          return new FactoryInjectorProcessor(compiler);
        }
      });


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
      if (passFactory.equals(closureRewriteClass) && !inserted) {
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
