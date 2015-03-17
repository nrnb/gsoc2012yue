Hi,

You're probably looking at my code sample.

The summer involved 2 main projects: an app manager for Cytoscape 3, and an alternative, faster renderer for Cytoscape Web/Cytoscape.js. The app manager code I didn't write is the HTTP server.

The renderer is Javascript, using HTML5 Canvas 2D context. Initially, tests were done with various Canvas libraries, as well as WebGL. Directly using WebGL (no library) was the fastest, but I had to use shaders. (See webgl_direct_fxaa3.11_bezier.html, faster-than-canvas Bezier curve drawing via vertex generation by shader). I came up with the idea and implementation of using transformation matrices to transfer control point data independently.

Thanks,
Yue
