var goog = {
      provide : function(){}
    };
goog.provide('camp');

camp = {
  /**
   * @param {Function} a
   * @param {Function} b
   * @param {Object=} opt_rename
   */
  mixin : function(a, b, opt_rename){
    opt_rename = opt_rename || {};
    var prop;

    for (prop in opt_rename) {
      a.prototype[prop] = b.prototype[opt_rename[prop]];
    }

    for (prop in b.prototype) {
      if (!(prop in a.prototype)) {
        a.prototype[prop] = b.prototype[prop];
      }
    }
  }
};

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
 */
function Test2() {

}

/**
 * @constructor
 * @implements {ITrait}
 */
function Trait() {}

/**
 *
 * @param {string} message
 */
Trait.prototype.setMessage = function(message) {
  this._setMessage(message);
};


/**
 * @returns {{mstDeviceList : string,
    mstOsList : string,
    osIds : string,
    deviceIds : string,
    selectedOs : string,
    selectedDevice : string}}
 */
Trait.prototype.getItems = function() {
  return {
    mstDeviceList : 'mstDeviceList',
    mstOsList : 'mstOsList',
    osIds : 'osIds',
    deviceIds : 'deviceIds',
    selectedOs : 'selectedOs',
    selectedDevice : 'selectedDevice'
  };
};


/**
 * @constructor
 */
function Trait2() {}

Trait2.prototype.hoge = function() {
  return '1000';
};



/**
 *
 * @type {string}
 */
Trait.prototype.defaultMessage = 'hogehoge';


/**
 * @param {string} message
 */
Trait.prototype._setMessage = function(message) {
};


camp.mixin(Trait2, Trait);

/**
 * @constructor
 */
function Test() {
  this._state = false;
}
camp.mixin(Test, Trait2);

/**
 * @param {string} message
 */
Test.prototype._setMessage = function(message) {
  document.getElementById('aaa').innerHTML = message;
};


var hoge = new Test();
hoge.setMessage(hoge.getItems().mstDeviceList);
