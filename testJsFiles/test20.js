camp.module("a.b.c", function (exports) {
  /**
   * @constructor
   */
  function Main() {}

  /**
   * @export
   */
  Main.prototype.fuga = function() {
    return 200;
  };


  document.getElementById('div').innerHTML = new Main().fuga();
});