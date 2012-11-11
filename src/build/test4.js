goog.provide('test');

//goog.scope(function () {
  /**
   * @typedef {!Function}
   */
test.XXX;
//})

/**
 * @constructor
 * @param {test.XXX} m
 */
test.fn = function (m) {
  this._value = m();
}

test.fn.prototype.get = function () {return this._value;}

function v () {
  return document.getElementById('test').innerHTML;
}

console.log(new test.fn().get())