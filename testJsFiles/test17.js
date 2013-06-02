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
 * @constructor
 */
function Test2() {

}

var Trait = {
      /**
       *
       * @type {string}
       */
      defaultMessage : 'hogehoge',


      /**
       * @this {Object}
       * @param {string} message
       */
      _setMessage : function(message) {
      },

      /**
       * @this {Object}
       * @param {string} message
       */
      setMessage : function(message) {
        this._setMessage(message);
      },

      /**
       * @this {Test}
       * @param {string} message
       */
      setMessage$$Test : function(message) {
        this._setMessage(message);
      },

      /**
       * @this {Test2}
       * @param {string} message
       */
      setMessage$$Test2 : function(message) {
        this._setMessage(message);
      },


      /**
       * @this {Object}
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
      },

      /**
       * @this {Test}
       * @returns {{mstDeviceList : string,
       mstOsList : string,
       osIds : string,
       deviceIds : string,
       selectedOs : string,
       selectedDevice : string}}
       */
      getItems$$Test : function() {
        return {
          mstDeviceList : 'mstDeviceList',
          mstOsList : 'mstOsList',
          osIds : 'osIds',
          deviceIds : 'deviceIds',
          selectedOs : 'selectedOs',
          selectedDevice : 'selectedDevice'
        };
      },

      /**
       * @this {Test2}
       * @returns {{mstDeviceList : string,
       mstOsList : string,
       osIds : string,
       deviceIds : string,
       selectedOs : string,
       selectedDevice : string}}
       */
      getItems$$Test2 : function() {
        return {
          mstDeviceList : 'mstDeviceList',
          mstOsList : 'mstOsList',
          osIds : 'osIds',
          deviceIds : 'deviceIds',
          selectedOs : 'selectedOs',
          selectedDevice : 'selectedDevice'
        };
      }
    };



var Trait2 = {
      /**
       * @this {Test}
       * @return {string}
       */
      hoge : function() {
        return '1000';
      },
      setMessage : Trait.setMessage,
      getItems : Trait.getItems,
      defaultMessage : Trait.defaultMessage,
      _setMessage : Trait._setMessage
    };

/**
 * @constructor
 */
function Test() {
  this._state = false;
}

Test.prototype.setMessage = Trait.setMessage$$Test;
Test.prototype.hoge = Trait2.hoge;

Test.prototype.getItems = Trait.getItems$$Test;
Test.prototype.defaultMessage = Trait.defaultMessage;

/**
 * @param {string} message
 */
Test.prototype._setMessage = function(message) {
  document.getElementById('aaa').innerHTML = message;
};

Test2.prototype.setMessage = Trait.setMessage$$Test2;
Test2.prototype.getItems = Trait.getItems$$Test2;

/**
 * @param {string} message
 */
Test2.prototype._setMessage = function(message) {
  document.getElementById('bbb').innerHTML = message;
};

var hoge = new Test();
var foo = new Test2();
hoge.setMessage(hoge.getItems().mstDeviceList);
foo.setMessage(foo.getItems().mstDeviceList);
