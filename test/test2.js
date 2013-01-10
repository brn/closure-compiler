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
 * @param {Function} classConstructor
 */
goog.addSingletonGetter = function (classConstructor) {
  classConstructor.getInstance = function () {
    if (!classConstructor._instance) {
      classConstructor._instance = new classConstructor;
    }
    return classConstructor._instance;
  }
}

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

camp.module('camp.dependencies', function (exports) {

  if (!goog.DEBUG) {
    /**
     * @type {Object}
     */
    exports.injectionRegistry = {}
  } else {
    /**
     * @private {Object}
     */
    exports._injectionsRegistry = {};

    /**
     * @type {Object}
     */
    exports.injector = {}


    /**
     * @param {string} name
     * @param {*} value
     */
    exports.injector.bind = function (name, value) {
      exports._injectionsRegistry[name] = value;
    }


    /**
     * @template T
     * @param {function(new:T,...):T} classConstructor
     * @returns {T}
     */
    exports.injector.createInstance = function (classConstructor) {
      var injections;
      var args;
      if (!classConstructor._injections) {
        classConstructor._injections = exports.injector._parseArguments(classConstructor);
      }
      injections = classConstructor._injections;
      args = exports.injector._createArguments(injections);
      if (classConstructor.getInstance) {
        return classConstructor.getInstance.apply(null, args);
      }
      return exports.injector._invokeNewCall(classConstructor, args);
    }


    /**
     * @template T
     * @param {function(new:T,...):void} classConstructor
     * @param {...string} var_args
     */
    exports.injector.inject = function (classConstructor, var_args) {
      var args = Array.prototype.slice.call(arguments);
      classConstructor = args.shift();
      if (!classConstructor._injections) {
        classConstructor._injections = exports.injector._parseArguments(classConstructor);
      } else {
        classConstructor._injections = classConstructor._injections
          .concat(exports.injector._parseArguments(classConstructor));
      }
    }


    /**
     * @template T
     * @param {function(new:T,...):T} classConstructor
     * @returns {Array.<string>}
     */
    exports.injector._parseArguments = function (classConstructor) {
      var args = Function.prototype.toString.call(classConstructor)
            .match(exports.injector._ARGUMENTS_REG);
      if (args && args[1]) {
        return args[1].split(',');
      } else {
        return [];
      }
    }


    /**
     * @private
     * @param {Array.<string>} injections
     * @returns {Array}
     */
    exports.injector._createArguments = function (injections) {
      var args = [];
      var injection;
      for (var i = 0, len = injections.length; i < len; i++) {
        injection = injections[i];
        if (injection in exports._injectionsRegistry) {
          injection = exports._injectionsRegistry[injection];
          if (typeof injection === 'function') {
            args[i] = exports.injector.createInstance(injection);
          } else {
            args[i] = injection;
          }
        } else {
          args[i] = null;
        }
      }
      return args;
    }


    /**
     * @template T
     * @param {function(new:T,...):T} classConstructor
     * @param {Array} injections
     * @returns {T}
     */
    exports.injector._invokeNewCall = function (classConstructor, injections) {
      var instance;
      /**
       * @constructor
       */
      function NewCallProxyClass () {}
      NewCallProxyClass.prototype = classConstructor.prototype;
      instance = new NewCallProxyClass;
      classConstructor.apply(instance, injections);
      return instance;
    }

    exports.injector.defineProvider = function (classConstructor, provider) {
      classConstructor._provider = provider;
    }

    /**
     * @const {RegExp}
     */
    exports.injector._ARGUMENTS_REG = /function[^\(]*\(([a-zA-Z_$][\w_$,]*)/;
  }

});