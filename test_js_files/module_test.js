camp.module("test.hoge.hoge", function (exports) {

  /**
   * @typedef {(number|string)}
   */
  var Typedef;

  var A = camp.using('test.hoge.hoge.A');
  var C = camp.using('test.hoge.hoge.Ba').x;
  //camp.using('test.hoge.hoge.Btest');
  A.hoge();

  /**
   * @constructor
   */
  function B() {
    this.node = document.getElementById('div');
  }
  B.prototype.a = function (o) {
    this.node.innerHTML = o;
  };

  (function() {
    var s = new B;
    s.a('a');
    C();
  })();

  /**
   * @param {Typedef} X
   */
  exports.hoge = function(X) {
    var s = new B;
    var v = new A('a');
    s.a(X);
    v.hoge();
  };

  exports.hoge('hoge');

  exports.B = B;
});