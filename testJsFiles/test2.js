/**
 * @template T
 * @param {function(new:T,...):void} fn
 */
camp.singleton = function (fn) {
  /**
   * @constructor
   */
  function e () {}
  e.prototype = fn.prototype;
  fn.getInstance = function () {
    if (fn._ins) {
      return fn._ins;
    } else {
      var ret = new e();
      fn._ins = ret;
      fn.apply(ret, arguments);
    }
    return ret;
  }
}

var goog = {};

/**
 * Adds a {@code getInstance} static method that always return the same instance
 * object.
 * @param {!Function} ctor The constructor for the class to add the static
 *     method to.
 */
goog.addSingletonGetter = function(ctor) {
  ctor.getInstance = function () {
    if (ctor.instance_) {
      return ctor.instance_;
    }
    if (goog.DEBUG) {
      // NOTE: JSCompiler can't optimize away Array#push.
      goog.instantiatedSingletons_[goog.instantiatedSingletons_.length] = ctor;
    }
    return ctor.instance_ = new ctor;
  };
};

/**
 * Inherit the prototype methods from one constructor into another.
 *
 * Usage:
 * <pre>
 * function ParentClass(a, b) { }
 * ParentClass.prototype.foo = function(a) { }
 *
 * function ChildClass(a, b, c) {
 *   goog.base(this, a, b);
 * }
 * goog.inherits(ChildClass, ParentClass);
 *
 * var child = new ChildClass('a', 'b', 'see');
 * child.foo(); // works
 * </pre>
 *
 * In addition, a superclass' implementation of a method can be invoked
 * as follows:
 *
 * <pre>
 * ChildClass.prototype.foo = function(a) {
 *   ChildClass.superClass_.foo.call(this, a);
 *   // other code
 * };
 * </pre>
 *
 * @param {Function} childCtor Child class.
 * @param {Function} parentCtor Parent class.
 */
goog.inherits = function(childCtor, parentCtor) {
  /** @constructor */
  function tempCtor() {};
  tempCtor.prototype = parentCtor.prototype;
  childCtor.superClass_ = parentCtor.prototype;
  childCtor.prototype = new tempCtor();
  /** @override */
  childCtor.prototype.constructor = childCtor;
};

/**
 * @define {boolean}
 */
goog.DEBUG = false;

camp.module('camp.injections', ['Injector', 'Module', 'modules', 'Matchers', 'Scopes'], function (exports) {
  //コンパイル後に消える
  if (!goog.DEBUG) {
    /**
     * コンパイル後にグローバルな型以外はここに集められる。
     * @type {Object}
     */
    exports.injectionRegistry = {};
  } else {
    /**
     * @constructor
     * @param {Binder} binderList
     */
    exports.Injector = function(binderList) {
      this._injections = {};
      this._interceptors = [];
      binderList.forEach(function(binder) {
        var injections = binder.getInjections();
        for (var prop in injections) {
          this._injections[prop] = injections[prop];
        }
        this._interceptors = this._interceptors.concat(binder.getInterceptors());
      }, this);
    };
    exports.Injector.prototype.createInstance = function(classConstructor) {
      return this._doCreate(classConstructor);
    };
    exports.Injector.inject = function(classConstructor, var_args) {
    };

    /**
     * @interface
     */
    exports.Module = function() {};

    exports.Module.prototype.configure = function(binder) {};

    exports.modules = {
      init : function() {}
    };
    exports.Matchers = function(){};
    exports.JointPoint = function(){};

    exports.Scopes = {
      SINGLETON : 'singleton',
      EAGER_SINGLETON : 'eagerSingleton'
    };
  }
});