goog.provide('camp.TestClass1');
goog.provide('camp.TestClass2');

/**
 * @constructor
 */
camp.TestClass1 = function() {
  /**
   * @private {camp.TestClass2}
   */
  this._testClass2 = this._internal();
}

/**
 * @returns {string}
 */
camp.TestClass1.prototype.getMyName = function() {
  return this._testClass2.getMyName() + 'name';
};


/**
 * @constructor
 */
camp.TestClass2 = function() {
  /**
   * @private {string}
   */
  this._name = 'name';
}
/**
 * @returns {string}
 */
camp.TestClass2.prototype.getMyName = function() {
  return this._name;
};

/**
 * @returns {camp.TestClass2}
 */
function provider() {
  return new camp.TestClass2;
};

/**
 * @private {function():camp.TestClass2}
 */
camp.TestClass1.prototype._internal = provider;



/**
 * @type {camp.TestClass1}
 */
var testClass1 = new camp.TestClass1;
document.getElementById('aaa').innerHTML = testClass1.getMyName();