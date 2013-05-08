goog.provide('goog.reflect');
goog.provide('goog.object');


/**
 * Syntax for object literal casts.
 * @see http://go/jscompiler-renaming
 * @see http://code.google.com/p/closure-compiler/wiki/
 *      ExperimentalTypeBasedPropertyRenaming
 *
 * Use this if you have an object literal whose keys need to have the same names
 * as the properties of some class even after they are renamed by the compiler.
 *
 * @param {!Function} type Type to cast to.
 * @param {Object} object Object literal to cast.
 * @return {Object} The object literal.
 */
goog.reflect.object = function(type, object) {
  return object;
};

goog.object.extend = function(a,b) {
  for (var prop in b) {
    a[prop] = b[prop];
  }
};

camp.module("camp.test.main", function (exports) {
  var modules = camp.using('camp.injections.modules');
  var DefaultModule = camp.using('camp.vm.interaction.DefaultModule');
  var DefaultModule2 = camp.using('camp.vm.interaction.DefaultModule2');
  var Test3 = camp.using('camp.vm.interaction.Test3');
  var Test = camp.using('camp.vm.interaction.Test');
  var DataSourceManager = camp.using('camp.vm.interaction.DataSourceManager');
  var Test4 = camp.using('camp.vm.interaction.Test4');
  var Service = camp.using('camp.vm.interaction.Service');
  var Hoge = camp.using('camp.vm.interaction.Hoge');
  var Base3 = camp.using('camp.vm.interaction.Base3');

  /**
   * @export
   */
  function Hoge() {

  }

  /**
   * @export
   */
  Hoge.prototype.setService = function() {

  };

  exports.main = function () {
    modules.init([DefaultModule, DefaultModule2], function (injector) {
      var l = injector.getInstance(Test3);
      var v = injector.getInstance(Test);
      var o = injector.getInstanceByName('dataSourceManager');
      var x = injector.getInstance(Base3);
      o.echo(l.getName() + v.getName());
      window.localStorage['foo'] = l.getName() + v.getName();
      window.console.log(injector.getInstance(Test4));
      x.insert();
    });
    modules.init([DefaultModule], function (injector) {
      var o = injector.getInstance(Service);
      o.getNode().innerHTML = 'hogehoge';
    });

    goog.object.extend(DefaultModule.prototype, /**@lends {DefaultModule.prototype}*/({test : function() {window.console.log('ok!')}}));
    new DefaultModule().test();
  };
});