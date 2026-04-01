Detecting memory leaks
==

## Prior art:
BLeak paper (https://github.com/plasma-umass/BLeak/tree/master)

We need to take multiple snapshots. Each object is identified by its shortest retained path from roots.

We look for objects that increase in the number of references between snapshots.

Thoughts: not sure how useful this is directly? The LeakShare metric might be worth comparing

## Leak examples

### Allocation backtrace (FF has this)
```
        setInterval(() => {
            myGlobal.arr.push(new Array(1000))
        }, 1000);
```

This can be detected via heap diff + allocation backtraces in FF, not in WI.

FF/WI also lets you see the retained path (global lexical scope)->myGlobal->arr->[x] if you click on an individual object in one snapshot. In WI you need to hover over the object to see "shortest retained path"

Proposal:
- collect allocation backtraces for heap snapshots / diffs
- let you go from a heap diff entry to an individual allocation (FF misses this too)
- 

### Assignment backtrace

```
myGlobal.arr.push(polyVariantMake())
            myGlobal.arr.push(polyVariantMake2())
            myGlobal.arr.push(polyVariantMake3())
            myGlobal.arr.push(polyVariantMake4())
```

Here, FF diff lets you see polyvariantMake, but that's it. We need to get the assignment backtrace here too.

Retained path lets you see myglobal.arr[x] still.

Proposal:
- Write barrier slow path to get assignment backtraces?

## Retained path through cpp

```
globObj.arr.push(new MessageEvent("test", { data: { xxxxxx: new Uint16Array(1000) } }))
```

FF is confused by this too.

Having a real retained path would let us see globObj.arr, which might help us track down the leak.

Proposal:
 We should find a way to see through Strong<> references.
- Try to build with C++26 support
- Use reflection to add a new Traceable trait to everything (notably this would apparently not require any code changes to existing classes)
- Trace each object using reflection to discover every path ending in a Strong<>, starting from Document

## Growth inside cpp container

```
let globObj = { e: new MessageEvent("test", { data: { xxxxxx: [] } }) }
      setInterval(() => {
          globObj.e.data.xxxxxx.push(new Uint16Array(1000))
      }, 100)
```

Here too having a real retained path would make this possible (not easy) to find.


## Closures

```
      let x = () => 0
      setInterval(() => {
          x = (function() {
            let arr = new Uint16Array(1000)
            let copy = x
            return () => copy() + arr.length
          })()
      }, 100)
```
Firefox gives us a nice retained path x.copy.[...].arr, but no backtrace

```
      setInterval(() => {
          window.base.onClick = (function() {
            let prev = window.base.onClick
            let x = new Uint16Array(1000)
            return (a) => a + prev(x.length)
          })()
      }, 100)
```

Here we get DivElement as the root in FF and that's it.
      

## Multiple (3+) snapshot diffing

Proposal:
- Identify objects by path (with identifiers), not identity
- Show list of subtrees that grew in every snapshot (like BLeak)
- Show all non-cyclic paths that end in the leaking object


## Retaining Document

```
      const strings = [];
      setInterval(() => {
        const iframe = document.createElement('iframe');
        document.body.appendChild(iframe);
        const str = iframe.contentWindow.eval('window.obj = new Uint16Array(1000); String("hi")');
        iframe.remove();
        strings.push(str);
      }, 100);
    </script>
```

This is so cursed but totally possible in practice.

There is no association whatsoever with the iframe and strings[].

# Implementation

- Collect allocation stack traces, store in side table
- Add CPP objects to snapshots
- Use $vm to write JSTests before implementing anything in WI

Should this be a WI feature, or should we make our own tool?

- Collect a subset of mutation backtraces
    - Collect a stack trace any time we hit a write barrier (force slow path):
    - target.label = foo, target[x] = foo, etc
        - We would only see target
    - CPP write barriers will expose internals (adding an event listener, appending a dom node)
    - target.label = null doesn't need a barrier, will this cause too many false positives?

- Allow grouping leaks by allocation site, mutation site, and by retained path

Ex:
```
        setInterval(() => {
            myGlobal.arr.push(new Array(1000))
        }, 1000);
```


myGlobal.arr would get this site added to its list of backtraces

```
myGlobal.arr.push(polyVariantMake())
            myGlobal.arr.push(polyVariantMake2())
            myGlobal.arr.push(polyVariantMake3())
            myGlobal.arr.push(polyVariantMake4())
```

4 sites would be added to myGlobal.arr

```
globObj.arr.push(new MessageEvent("test", { data: { xxxxxx: new Uint16Array(1000) } }))
```

globObj.arr would see the push site. globObj.arr[x].[internal] would not see the construction site of data.xxxxxx, since there is no write barrier

With Strong<> tracing, we could caluclate the retained size of globObj.arr correctly though, and still find this leak

```
let globObj = { e: new MessageEvent("test", { data: { xxxxxx: [] } }) }
      setInterval(() => {
          globObj.e.data.xxxxxx.push(new Uint16Array(1000))
      }, 100)
```

xxxxxx would get this site added

```
      let x = () => 0
      setInterval(() => {
          x = (function() {
            let arr = new Uint16Array(1000)
            let copy = x
            return () => copy() + arr.length
          })()
      }, 100)
```

Allocation backtraces would help in this case, but not if the allocation is done in a helper (which is common)

There may not be write barriers here to help us: when arr is assigned, do we need to WB the lexical environment?

For this, we might just want to associate the source location that creates the environment as a special case, and hope these functions don't get too big.


```
      setInterval(() => {
          window.base.onClick = (function() {
            let prev = window.base.onClick
            let x = new Uint16Array(1000)
            return (a) => a + prev(x.length)
          })()
      }, 100)
```

Here we would see DivElement.onClick as the full path unless we walk the entire cpp object graph.

This might still be helpful if we can collect the assignment site

We would need some way to group leaks by assignment site instead of by path though, in case we have a diverse set of dom elements.

Then we would see that 1) DivElement.onClick is a growing subtree, and attribute its retained size to this site, and 2) when grouping each mutation site by retained size, this shows up near the top

```
      const strings = [];
      setInterval(() => {
        const iframe = document.createElement('iframe');
        document.body.appendChild(iframe);
        const str = iframe.contentWindow.eval('window.obj = new Uint16Array(1000); String("hi")');
        iframe.remove();
        strings.push(str);
      }, 100);
    </script>
```

Here we would ideally want the retained path to be visible as strings[x].<constraint-marks>.globalObject.<constraint-marks>.document. Reflection won't get us here, we would need to instrument every visitChildren method whenever it has some kind of conditional marking.

This would probably also require us to see all paths, since it is likely that some of these frames will still be reachable by non-strong conservative roots.



