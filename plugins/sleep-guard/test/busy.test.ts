/**
 * Tests for the busy-detection decision logic.
 *
 * These run against plain data with no DSH host and no child processes, which
 * is the point of splitting `busy.ts` out: the question "may this machine
 * sleep?" is the part worth testing exhaustively, and it should not require a
 * running agent to do so.
 */

import { test, describe } from 'node:test'
import assert from 'node:assert/strict'

import {
  BusyTracker,
  isBusy,
  isJobOccupying,
  type AgentLike,
  type JobLike,
  type JobStatus,
} from '../src/busy.ts'

const idle: AgentLike = { status: 'idle' }
const running: AgentLike = { status: 'running' }

const job = (status: JobStatus): JobLike => ({ status })

describe('isJobOccupying', () => {
  test('treats running as occupying', () => {
    assert.equal(isJobOccupying('running'), true)
  })

  // The regression this test exists for: an earlier draft only counted
  // 'running', which let the machine sleep while a job was still shutting down.
  test('treats stopping as occupying, because the work has not finished', () => {
    assert.equal(isJobOccupying('stopping'), true)
  })

  test('treats every settled state as not occupying', () => {
    for (const status of ['completed', 'killed', 'failed'] as const) {
      assert.equal(isJobOccupying(status), false, `${status} should not occupy`)
    }
  })
})

describe('isBusy', () => {
  test('is false with nothing at all', () => {
    assert.equal(isBusy([], []), false)
  })

  test('is false when every agent is idle and every job has settled', () => {
    assert.equal(isBusy([idle, idle], [job('completed'), job('killed'), job('failed')]), false)
  })

  test('is true when any agent is running', () => {
    assert.equal(isBusy([idle, running], []), true)
  })

  // The other half of the split brain: `agent.status === 'idle'` means no
  // driver is scheduled, not that nothing is happening. A background bash job
  // outlives the turn that started it.
  test('is true when every agent is idle but a job is still running', () => {
    assert.equal(isBusy([idle], [job('completed'), job('running')]), true)
  })

  test('is true when a job is stopping', () => {
    assert.equal(isBusy([idle], [job('stopping')]), true)
  })

  test('does not mutate the arrays it is given', () => {
    const agents = [idle]
    const jobs = [job('running')]
    isBusy(agents, jobs)
    assert.deepEqual(agents, [idle])
    assert.deepEqual(jobs, [job('running')])
  })
})

describe('BusyTracker', () => {
  test('does not fire when nothing changed', () => {
    const seen: boolean[] = []
    const tracker = new BusyTracker((busy) => { seen.push(busy) })
    tracker.update([], [])
    tracker.update([idle], [job('completed')])
    tracker.update([], [])
    assert.deepEqual(seen, [], 'idle-to-idle must not fire')
    assert.equal(tracker.busy, false)
  })

  test('fires exactly once on the idle to busy edge', () => {
    const seen: boolean[] = []
    const tracker = new BusyTracker((busy) => { seen.push(busy) })
    tracker.update([running], [])
    tracker.update([running], [])
    tracker.update([running, idle], [])
    assert.deepEqual(seen, [true], 'a repeated busy snapshot must not re-fire')
    assert.equal(tracker.busy, true)
  })

  test('fires exactly once on the busy to idle edge', () => {
    const seen: boolean[] = []
    const tracker = new BusyTracker((busy) => { seen.push(busy) })
    tracker.update([running], [])
    tracker.update([idle], [])
    tracker.update([], [job('completed')])
    assert.deepEqual(seen, [true, false])
    assert.equal(tracker.busy, false)
  })

  test('survives a handoff where one agent stops as a job starts', () => {
    const seen: boolean[] = []
    const tracker = new BusyTracker((busy) => { seen.push(busy) })
    tracker.update([running], [])
    tracker.update([idle], [job('running')])
    assert.deepEqual(seen, [true], 'work continuing across a handoff is not an edge')
    assert.equal(tracker.busy, true)
  })

  test('reports the new state to the callback, matching the getter', () => {
    const seen: Array<[boolean, boolean]> = []
    const tracker = new BusyTracker((busy) => { seen.push([busy, tracker.busy]) })
    tracker.update([running], [])
    tracker.update([idle], [])
    assert.deepEqual(seen, [[true, true], [false, false]])
  })

  test('keeps its state consistent when the callback throws', () => {
    let calls = 0
    const tracker = new BusyTracker(() => {
      calls += 1
      throw new Error('callback exploded')
    })
    assert.throws(() => { tracker.update([running], []) }, /callback exploded/)
    // State advanced before the throw, so a retry must not double-fire — a
    // duplicated callback would leak a second assertion process.
    assert.equal(tracker.busy, true)
    assert.equal(tracker.update([running], []), true)
    assert.equal(calls, 1)
  })

  test('becomes inert after dispose, so a late event cannot resurrect a process', () => {
    const seen: boolean[] = []
    const tracker = new BusyTracker((busy) => { seen.push(busy) })
    tracker.update([running], [])
    tracker.dispose()
    tracker.update([idle], [])
    assert.deepEqual(seen, [true], 'no release is requested after disposal')
    assert.equal(tracker.busy, true, 'state is frozen, not reset')
  })
})
