camp.module("test.module", function (exports) {

  /**
   * @interface
   */
  function Module() {

  }

  /**
   * @param {Object} bindings
   */
  Module.prototype.configure = function(bindings) {

  };

  /**
   * @param {...Module} var_args
   * @return {Object}
   */
  function configure(var_args) {
    var bindings = {};
    for (var i = 0, len = arguments.length;i < len;i++) {
      new arguments[i]().configure(bindings);
    }
    return bindings;
  }

  function getItemOf() {
    return document.getElementById('hoge').innerHTML;
  }

  /**
   * @constructor
   */
  function Foo(fooName) {
    this._name = fooName;
  }
  Foo.prototype.getFooName = function() {
    return this._name;
  };

  /**
   * @constructor
   * @param {string} item1
   * @param {string} item2
   * @param {Foo} foo
   */
  function Test(item1, item2, foo) {
    this.item = item1;
    this.item2 = item2;
    this.foo = foo;
  }

  Test.prototype.toString = function() {
    return this.item + this.item2 + 'aaaa' + this.foo.getFooName();
  };

  /**
   * @constructor
   * @param {string} item
   * @param {string} item2
   */
  function Test2(item, item2) {
    this.item = item;
    this.item2 = item2;
  }

  Test2.prototype.toString = function() {
    return this.item + this.item2 + 'aaaa';
  };

  /**
   * @constructor
   * @implements {Module}
   */
  function Module1() {};
  Module1.prototype.configure = function(bindings) {
    /**
     * @returns {string}
     */
    bindings.getItem1 = function() {
      return getItemOf();
    };
  };

    /**
   * @constructor
   * @implements {Module}
   */
  function Module2() {

  }

  Module2.prototype.configure = function(bindings) {
    /**
     * @returns {string}
     */
    bindings.getItem2 = function() {
      return 'item2';
    };

    /**
     * @return {Foo}
     */
    bindings.getFoo = function() {
      return camp.dependencies.injector.newInstance(Foo, bindings);
    };

    /**
     * @return {string}
     */
    bindings.getFooName = function() {
      return 'fooName';
    };

    bindings.getItem1 = function() {
      return 'not test 1';
    };
  };

  var bindings = configure(new Module1(), new Module2());

  var t = camp.dependencies.injector.newInstance(Test, bindings);
  document.getElementById('aaaa').innerHTML = t.toString();
});