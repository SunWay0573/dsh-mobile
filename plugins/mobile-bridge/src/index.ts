/**
 * mobile-bridge — the outbound half of remote control.
 *
 * DSH can tell a connected client that it needs a decision, but it cannot
 * reach a client that is not there. That is the gap this closes: a phone that
 * is in someone's pocket still finds out that the agent is blocked.
 *
 * @module dsh-mobile-mobile-bridge
 */

import type { Context } from '@deepseek-ai/cordis'
import z from '@deepseek-ai/schemastery'
// Type-only and deliberately empty: pulls in the `approval/request` declaration
// merge. Erased at runtime.
import type {} from '@deepseek-ai/dsh-user-approval'
import type { Agent } from '@deepseek-ai/dsh-agent'
import { defineTool } from '@deepseek-ai/dsh-tools'

import { RateLimiter, approvalNeededMessage, turnFinishedMessage } from './events.ts'
import { Notifier } from './notify.ts'
import { ScreenCurtain } from './curtain.ts'
import { WakeBridge } from './wake.ts'

export const name = 'dsh-mobile-mobile-bridge'

/**
 * `agents` supplies the live agents whose status transitions signal a finished
 * turn. The approval event needs no service — it arrives through ordinary
 * event dispatch. `tools` is injected optionally inside `apply`, so a host
 * without the tools registry still gets notifications.
 */
export const inject = ['agents']

/** Plugin configuration. */
export interface Config {
  /**
   * Endpoint to POST notifications to. For ntfy this is
   * `https://ntfy.sh/<topic>`; the topic string is a password, so use a long
   * random value.
   *
   * Without this the plugin loads and does nothing, which is the correct
   * behaviour for a host that has not been set up for remote use.
   */
  url?: string
  /** Bearer token for a protected topic or a self-hosted instance. */
  token?: string
  /**
   * Public base URL of this host, used to build deep links back into a
   * session. Without it notifications arrive without a tap target.
   */
  baseUrl?: string
  /** Notify when the agent is blocked on an approval decision. */
  notifyOnApproval?: boolean
  /** Notify when a turn finishes. */
  notifyOnTurnEnd?: boolean
  /** Maximum notifications per session per minute. */
  rateLimitPerMinute?: number
  /**
   * Base URL of a `wol-bridge` on your LAN, e.g. `http://127.0.0.1:8787`.
   * Without it the wake tool is not registered: a tool that can only fail is
   * worse than no tool, because the agent will call it and report a confusing
   * error instead of saying the feature is unconfigured.
   */
  wakeBridgeUrl?: string
  /** Shared secret for that bridge, when it requires one. */
  wakeBridgeToken?: string
  /** Target MAC for the wake tool, when the bridge is not preconfigured. */
  wakeMac?: string
  /** Register the `lock_screen` and `wake_computer` tools. */
  tools?: boolean
}

export const Config: z<Config> = z.object({
  url: z.string().description('Webhook endpoint, e.g. https://ntfy.sh/<topic>'),
  token: z.string().description('Bearer token for the endpoint'),
  baseUrl: z.string().description('Public base URL of this host, for deep links'),
  notifyOnApproval: z.boolean().default(true)
    .description('Notify when the agent needs an approval decision'),
  notifyOnTurnEnd: z.boolean().default(true)
    .description('Notify when a turn finishes'),
  rateLimitPerMinute: z.natural().default(20)
    .description('Maximum notifications per session per minute'),
  wakeBridgeUrl: z.string().description('Base URL of a wol-bridge on the LAN'),
  wakeBridgeToken: z.string().description('Shared secret for the wake bridge'),
  wakeMac: z.string().description('Target MAC address for the wake tool'),
  tools: z.boolean().default(true)
    .description('Register the lock_screen and wake_computer tools'),
})

/**
 * Mount the plugin.
 *
 * @param ctx - host context.
 * @param config - validated plugin configuration.
 */
export function apply(ctx: Context, config: Config): void {
  const url = config.url
  if (url === undefined || url === '') {
    ctx.logger.info(
      'mobile-bridge: no notification url configured; not sending anything'
      + ' (set config.url to enable)',
    )
    return
  }

  const notifier = new Notifier({
    url,
    token: config.token,
    onWarn: (message) => { ctx.logger.warn(`mobile-bridge: ${message}`) },
  })
  const limiter = new RateLimiter({
    limit: config.rateLimitPerMinute ?? 20,
    windowMs: 60_000,
  })

  ctx.logger.info(`mobile-bridge: notifications enabled (${url})`)

  ctx.effect(() => {
    const disposers: Array<() => void> = []

    const deliver = (sessionId: string | undefined, build: () => Parameters<Notifier['send']>[0]): void => {
      const key = sessionId ?? 'unknown'
      if (!limiter.allow(key)) {
        ctx.logger.warn(`mobile-bridge: rate limited a notification for ${key}`)
        return
      }
      // Fire and forget. A notification is a courtesy, and a courtesy must
      // never delay — let alone fail — the work that triggered it.
      void notifier.send(build()).catch((error: unknown) => {
        ctx.logger.warn(`mobile-bridge: send failed: ${describe(error)}`)
      })
    }

    if (config.notifyOnApproval !== false) {
      disposers.push(ctx.on('approval/request', (req, next) => {
        const sessionId = req.agent?.session?.id
        deliver(sessionId, () => approvalNeededMessage({
          toolName: req.toolName,
          reason: req.reason,
          sessionId,
          baseUrl: config.baseUrl,
        }))
        // Delegate unconditionally and immediately. This is a waterfall: a
        // listener that returns without calling `next()` claims the request
        // and the real answerer never runs, which would turn an observer into
        // a silent auto-deny. Notifying is also deliberately not awaited —
        // the approval decision must not wait on an HTTP request to a phone.
        return next()
      }))
    }

    if (config.notifyOnTurnEnd !== false) {
      disposers.push(installTurnEndWatch(ctx, (sessionId) => {
        deliver(sessionId, () => turnFinishedMessage({
          sessionId,
          baseUrl: config.baseUrl,
        }))
      }))
    }

    // Expired rate-limit keys would otherwise accumulate for the life of the
    // host, one per session ever seen.
    const prune = setInterval(() => { limiter.prune() }, 5 * 60_000)
    prune.unref?.()

    return () => {
      clearInterval(prune)
      for (const dispose of disposers) {
        try {
          dispose()
        } catch (error) {
          ctx.logger.warn(`mobile-bridge: listener teardown failed: ${describe(error)}`)
        }
      }
    }
  }, 'mobile-bridge.notifications()')

  if (config.tools !== false) registerTools(ctx, config)
}

/**
 * The shape both tools return.
 *
 * A tool must declare its output schema — without one the return type infers as
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
function registerTools(ctx: Context, config: Config): void {
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

/**
 * Notify when an agent goes from working to idle.
 *
 * Edge-detected per agent: `agent/status` reports the state just *entered*, so
 * a naive listener would notify on every idle event including the many that
 * arrive while nothing ever happened.
 *
 * @param ctx - host context.
 * @param notify - called with the session id on a running→idle edge.
 * @returns disposer.
 */
function installTurnEndWatch(
  ctx: Context,
  notify: (sessionId: string | undefined) => void,
): () => void {
  const previous = new Map<Agent, string>()
  return ctx.on('agent/status', ({ agent, status }) => {
    const sessionId = agent.session?.id
    const before = previous.get(agent) ?? 'idle'
    previous.set(agent, status)
    if (before === 'running' && status === 'idle') notify(sessionId)
  })
}

function describe(error: unknown): string {
  return error instanceof Error ? error.message : String(error)
}
