goog.provide('goog.reflect');
goog.provide('Interface');


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

goog.mixin = function(a,b) {
  for (var prop in b) {
    a[prop] = b[prop];
  }
};

var Interface = {
  addImplementations : function(iface, impl) {
    iface.prototype = impl;
  }
};

camp.module("camp.test.main", function (exports) {
  var injector = camp.using('camp.dependencies.newInstanceor');
  var Module = camp.using('camp.vm.interaction.Module');
  var Test3 = camp.using('camp.vm.interaction.Test3');
  var Test = camp.using('camp.vm.interaction.Test');
  var DataSourceManager = camp.using('camp.vm.interaction.DataSourceManager');
  var Test4 = camp.using('camp.vm.interaction.Test4');
  var Service = camp.using('camp.vm.interaction.Service');
  var Hoge = camp.using('camp.vm.interaction.Hoge');
  var Base3 = camp.using('camp.vm.interaction.Base3');
  var CalendarCacheManager = camp.using('camp.vm.interaction.CalendarCacheManager');


  /**
   * @interface
   */
  function InterfaceDef() {
    this.x = function(){};
  }

  InterfaceDef.prototype.test = function() {};

  Interface.addImplementations(InterfaceDef, {
    test : function() {window.console.log('ok!')}
  });

  /**
   * @interface
   */
  function InterfaceDef2() {
  }

  InterfaceDef2.prototype.test2 = function() {

  };

  Interface.addImplementations(InterfaceDef2, {
    test2 : function() {
      window.console.log('ok2!');
    }
  });

  /**
   * @template T
   * @this {T}
   */
  function trait() {
    this.test = function() {
      window.console.log('ok');
    };
  }


  /**
   * @constructor
   */
  function Hoge() {
    trait.call(this);
  }

  /**
   * @param {InterfaceDef} a
   */
  function xxx(a) {
    a.test();
  }


  exports.main = function () {
    var module = new Module();

    var l = injector.newInstance(Test3, module);
    var v = injector.newInstance(Test, module);
    var o = module.getDataSourceManager();
    var x = injector.newInstance(Base3, module);
    var m = injector.newInstance(CalendarCacheManager, module);
    o.echo(l.getName() + v.getName());
    window.localStorage['foo'] = l.getName() + v.getName();
    window.console.log(injector.newInstance(Test4, module));
    x.insert();
  };
});