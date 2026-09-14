# ashlar-mcp

MCP server for [Ashlar](https://github.com/rcwalter24/ashlar): lets Claude Desktop, Claude Code, Cursor or any MCP client survey, render, build, inspect and snapshot a live Minecraft Paper server. Requires the Ashlar plugin on the server and Node >= 22 here.

```sh
MC_PLUGIN_URL=ws://<server-ip>:8765 MC_PLUGIN_TOKEN=<token> npx ashlar-mcp --stdio
```

Full setup (plugin install, client config, HTTP mode, security notes): https://github.com/rcwalter24/ashlar#readme
