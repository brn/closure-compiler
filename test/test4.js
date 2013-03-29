
camp.module("camp.test.main", function (exports) {
  var modules = camp.using('camp.dependencies.modules');
  var DefaultModule = camp.using('camp.vm.interaction.DefaultModule');
  var DefaultModule2 = camp.using('camp.vm.interaction.DefaultModule2');
  var Test3 = camp.using('camp.vm.interaction.Test3');
  var Test = camp.using('camp.vm.interaction.Test');
  var DataSourceManager = camp.using('camp.vm.interaction.DataSourceManager');
  var Test4 = camp.using('camp.vm.interaction.Test4');
  var Service = camp.using('camp.vm.interaction.Service');

  exports.main = function () {
    modules.init([DefaultModule, DefaultModule2], function (injector) {
      var l = injector.createInstance(Test3);
      var v = injector.createInstance(Test);
      var o = injector.createInstance(DataSourceManager);
      o.echo(l.getName() + v.getName());
      window.localStorage['foo'] = l.getName() + v.getName();
      window.console.log(injector.createInstance(Test4));
    });
    modules.init([DefaultModule], function (injector) {
      var o = injector.createInstance(Service);
      o.getNode().innerHTML = 'hogehoge';
    });
  };
});