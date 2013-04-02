camp.module("camp.injections", function (exports) {
  var Binder = camp.using('camp.injections.Binder');
  var Injector = camp.using('camp.injections.Injector');

  if (goog.DEBUG) {
    /**
     * @interface
     */
    exports.Module = function() {};

    exports.Module.prototype.configure = function(binder) {};

    exports.module = {};

    exports.module.init = function(configList, closure) {
      var binders = [];
      configList.forEach(function(config) {
        var binder = new Binder;
        binders.push(binder);
        new config().configure(binder);
      });

      var injector = new Injector(binders);
      closure(injector);
    };
  }
});