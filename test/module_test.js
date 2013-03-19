var camp = {
      hoge : {}
    };

/**
 * @constructor
 */
camp.hoge.X = function () {
  this._item = 200;
};

(function() {
  /**
   * @param {camp.hoge.X} x
   */
  function v(x) {
    document.getElementById('div').innerHTML = x;
  }

  v(new camp.hoge.X);
})();

/**
 * @param {camp.hoge.X} x
 */
camp.hoge.y = function(x) {
  document.getElementById('div').innerHTML = x;
};

camp.hoge.y(new camp.hoge.X);