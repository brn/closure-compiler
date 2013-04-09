/**
 * @constructor
 */
function Test() {

}

/**
 * aの設定
 * @param {number} a
 */
Test.prototype.setA = function(a) {
  this._a = a;
};


var x = (x = new Test(),x.setA(100),x);