var goog = {
      provide : function(){}
    };
goog.provide('camp');

camp = {
  /**
   * @param {Function} a
   * @param {Function} obj
   */
  mixin : function(a, obj){
    for (var prop in obj) {
      if (!(prop in a.prototype)) {
        a.prototype[prop] = obj[prop];
      }
    }
  },

  trait : function(def) {
    return def;
  }
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
 * @constructor
 */
function TestBase() {}


/**
 * When defining a class Foo with an abstract method bar(), you can do:
 *
 * Foo.prototype.bar = goog.abstractMethod
 *
 * Now if a subclass of Foo fails to override bar(), an error
 * will be thrown when bar() is invoked.
 *
 * Note: This does not take the name of the function to override as
 * an argument because that would make it more difficult to obfuscate
 * our JavaScript code.
 *
 * @type {!Function}
 * @throws {Error} when invoked to indicate the method should be
 *   overridden.
 */
goog.abstractMethod = function() {
  throw Error('unimplemented abstract method');
};


/**
 * @interface ITrait
 */
function ITrait() {}
/**
 * @param {string} message
 */
ITrait.prototype.setMessage = function(message) {};

/**
 * @constructor
 * @extends {TestBase}
 */
function Test2() {

}
goog.inherits(Test2, TestBase);


var trait = camp.trait({
      /**
       * @param {string} message
       */
      setMessage : function(message) {
        this._setMessage(message);
      },

      /**
       * @returns {{mstDeviceList : string,
       mstOsList : string,
       osIds : string,
       deviceIds : string,
       selectedOs : string,
       selectedDevice : string}}
       */
      getItems : function() {
        return {
          mstDeviceList : 'mstDeviceList',
          mstOsList : 'mstOsList',
          osIds : 'osIds',
          deviceIds : 'deviceIds',
          selectedOs : 'selectedOs',
          selectedDevice : 'selectedDevice'
        };
      }
    });





var trait2 = camp.trait({
      hoge : function() {
        return '1000';
      }
    });

var trait3 = camp.trait({});
camp.mixin(trait3, [trait, trait2], {
  foo : function() {
    return 2000;
  },
  bar : function() {
    return 3000;
  }
});

/**
 * @constructor
 */
function Test() {
  this._state = false;
}
camp.mixin(Test, [trait, trait2], {
  /**
   * @return {Object}
   */
  getItems : function() {
    return {mstDeviceList : 'OK!'};
  }
});

/**
 * @param {string} message
 */
Test.prototype._setMessage = function(message) {
  document.getElementById('aaa').innerHTML = message;
};


var hoge = new Test();
hoge.setMessage(hoge.getItems().mstDeviceList);
