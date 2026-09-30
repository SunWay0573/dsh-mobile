/**
 * The two tools a remote operator asks the agent to run.
 *
 * Separated from `index.ts` on purpose: this module has a **value** dependency
 * on `@deepseek-ai/dsh-tools` for `defineTool`, and a module-level import that
 * fails to resolve aborts the whole plugin. That is not hypothetical — booting
 * this plugin in a real host produced
 *
 *     mobile-bridge (dsh-mobile-mobile-bridge): failed to import
 *
 * and took the notifications down with it.
 *
 * The first fix attempted was a lazy `import()` guarded by a catch, so a missing
 * package would degrade instead of aborting. That does not work: TypeScript's
 * `rewriteRelativeImportExtensions` rewrites **static** specifiers but leaves a
 * dynamic `import('./tools.ts')` untouched, so the emitted code asks for a `.ts`
 * file that does not exist next to it. The build succeeds and the host reports
 * only "failed to import".
 *
 * The real fix is the boring one: `@deepseek-ai/dsh-tools` is a dependency, not
 * a type-only dev dependency, because `defineTool` is a value.
 *
 * @module dsh-mobile-mobile-bridge/tools
 */

import type { Context } from '@deepseek-ai/cordis'
import { defineTool } from '@deepseek-ai/dsh-tools'

import type { Config } from './index.ts'
import { ScreenCurtain } from './curtain.ts'
import { WakeBridge } from './wake.ts'

/**
 * The shape both tools return.
 *
 * A tool must declare an output schema — without one the return type infers as
 * `never` and the definition does not typecheck. That is a useful constraint
 * rather than an obstacle: it forces the tool to say what it produces.
 */
const ACTION_OUTPUT_SCHEMA = {
  type: 'object',
  additionalProperties: false,
  properties: {
    ok: { type: 'boolean', required: true, description: 'Whether the action succeeded.' },
    message: { type: 'string', required: true, description: 'What happened, in one sentence.' },
  },
} as const

/**
 * Render the result for the model.
 *
 * The message alone, not the JSON: the model needs to know what happened, and a
 * sentence is what it will relay to the user. The `ok` flag is for the client,
 * which reads the structured value directly.
 */
function renderActionResult(
  _args: unknown,
  value: { readonly ok: boolean; readonly message: string },
): Array<{ type: 'text'; text: string }> {
  return [{ type: 'text', text: value.message }]
}

/**
 * Register the two tools a remote operator needs the agent to be able to run.
 *
 * `tools` is injected rather than declared, so a host without the tools
 * registry still gets notifications instead of failing to mount entirely.
 *
 * These exist because the trigger is the hard part, not the action. Locking a
 * screen is one command; knowing *when* to lock it, with no client-connection
 * signal to subscribe to, is not — so the operator asks, and the agent does it.
 *
 * @param ctx - host context.
 * @param config - plugin configuration.
 */
export function registerTools(ctx: Context, config: Config): void {
  const curtain = new ScreenCurtain()
  const wakeBridgeUrl = config.wakeBridgeUrl

  ctx.inject(['tools'], (toolCtx) => {
    toolCtx.effect(() => {
      const disposers: Array<() => void> = []

      disposers.push(toolCtx.tools.register(defineTool({
        name: 'lock_screen',
        description:
          'Lock the screen of the machine this agent runs on, so nobody standing '
          + 'at it can read the conversation or interfere. Unlocking requires the '
          + 'local password and cannot be done remotely — that is the point. Use '
          + 'when the operator is working remotely and wants privacy on site.',
        parameters: {},
        output: {
          schema: ACTION_OUTPUT_SCHEMA,
          render: renderActionResult,
        },
        async execute() {
          const result = await curtain.lock()
          return { ok: result.ok, message: result.message }
        },
      })))

      // Only registered when a bridge is configured. A tool that can only fail
      // would have the agent call it and report a confusing error rather than
      // saying the feature is unconfigured.
      if (wakeBridgeUrl !== undefined && wakeBridgeUrl !== '') {
        const bridge = new WakeBridge({
          url: wakeBridgeUrl,
          mac: config.wakeMac,
          token: config.wakeBridgeToken,
        })
        disposers.push(toolCtx.tools.register(defineTool({
          name: 'wake_computer',
          description:
            'Wake this machine if it is asleep, by asking a wol-bridge on the same '
            + 'network to send a Wake-on-LAN packet. Only meaningful when the machine '
            + 'is unreachable: a running host has nothing to wake.',
          parameters: {},
          output: {
            schema: ACTION_OUTPUT_SCHEMA,
            render: renderActionResult,
          },
          async execute() {
            const result = await bridge.wake()
            return { ok: result.ok, message: result.message }
          },
        })))
      }

      return () => {
        for (const dispose of disposers) {
          try {
            dispose()
          } catch (error) {
            ctx.logger.warn(`mobile-bridge: tool teardown failed: ${describe(error)}`)
          }
        }
      }
    }, 'mobile-bridge.tools()')
  })
}

function describe(error: unknown): string {
  return error instanceof Error ? error.message : String(error)
}
