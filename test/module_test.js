camp.module("test.hoge.hoge", function (exports) {

  /**
   * @typedef {(number|string)}
   */
  var Typedef;

  var A = camp.using('test.hoge.hoge.A');
  A.hoge();

  function B() {
    this.node = document.getElementById('div');
  }
  B.prototype.a = function (o) {
    this.node.innerHTML = o;
  };

  (function() {
    var s = new B;
    s.a('a');
  })();

  /**
   * @param {Typedef} X
   */
  exports.hoge = function(X) {
    var s = new B;
    s.a(X);
  };

  exports.hoge('hoge');
});