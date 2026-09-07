#!/usr/bin/env ruby
# Parses .claude/team/backlog-seed-draft.md into one JSON record per issue.
#
# An issue block is a heading (## or ###) whose text starts "<n>. ", followed within a few
# lines by a "- **Labels:**" bullet. Requiring the Labels bullet is what separates real issue
# blocks from the draft's analysis headings ("## Gaps I did not file", "## 5. ..."-shaped prose).
# Body runs to the next heading of level <= 3.
require 'json'

src = ARGV[0] or abort "usage: parse_draft.rb <draft.md> [--emit-dir DIR]"
emit_dir = (idx = ARGV.index('--emit-dir')) ? ARGV[idx + 1] : nil
lines = File.read(src).lines

HEADING = /^\#{1,3}\s/
NUMBERED = /^\#{2,3}\s*(\d+)\.\s+(.*?)\s*$/

issues = []
cur = nil

flush = lambda do
  return unless cur
  cur[:body] = cur[:body_lines].join.strip
  cur.delete(:body_lines)
  # Only a block that declared labels is an issue; the rest is prose that happened to be numbered.
  issues << cur unless cur[:labels].empty?
  cur = nil
end

lines.each do |ln|
  if ln =~ NUMBERED
    flush.call
    cur = { num: $1.to_i, title: $2, labels: [], milestone: nil, body_lines: [] }
    next
  elsif ln =~ HEADING
    flush.call
    next
  end
  next unless cur

  # The metadata bullets are not always the first thing in a block (a revised entry may lead
  # with a note), so match them anywhere and keep them out of the published body.
  if ln =~ /^-\s+\*\*Labels:\*\*\s*(.*)$/ && cur[:labels].empty?
    cur[:labels] = $1.scan(/`([^`]+)`/).flatten
  elsif ln =~ /^-\s+\*\*Milestone:\*\*\s*(.*)$/ && cur[:milestone].nil?
    cur[:milestone] = $1.strip
  else
    cur[:body_lines] << ln
  end
end
flush.call

# Later definition wins, so a Round 2 rewrite of an issue replaces the round 1 text.
by_num = {}
issues.each { |i| by_num[i[:num]] = i }
issues = by_num.values.sort_by { |i| i[:num] }

# A draft-process note ("*(Round 2 — revised ...)*") is bookkeeping between the Tech Lead and
# backlog-owner; it means nothing to someone reading the issue on GitHub. Strip it.
issues.each do |i|
  i[:body] = i[:body].sub(/\A\*\(Round \d+[^)]*\)\*\s*\n+/m, '').strip
end

issues.each do |i|
  abort "issue #{i[:num]} '#{i[:title]}' has no milestone"  if i[:milestone].to_s.empty?
  abort "issue #{i[:num]} '#{i[:title]}' has an empty body" if i[:body].to_s.strip.empty?
end

# Draft numbers are NOT GitHub issue numbers, and a bare "#4" in a published body would link
# to whatever issue #4 happens to be. Tokenize now; a second pass rewrites tokens to real
# numbers once the issues exist. In practice every "#<digits>" here is a draft cross-ref.
issues.each do |i|
  i[:body] = i[:body].gsub(/#(\d+)\b/) do
    n = $1.to_i
    by_num[n] ? "{{ISSUE:#{n}}}" : $~[0]
  end
end

issues.each do |i|
  i[:body].scan(/\{\{ISSUE:(\d+)\}\}/).flatten.map(&:to_i).uniq.each do |n|
    abort "issue #{i[:num]} references draft ##{n}, which is not in the draft" unless by_num[n]
  end
end

if emit_dir
  require 'fileutils'
  FileUtils.mkdir_p(emit_dir)
  issues.each do |i|
    File.write(File.join(emit_dir, "issue-#{'%02d' % i[:num]}.body.md"), i[:body] + "\n")
  end
end

puts JSON.pretty_generate(issues.map { |i| i.reject { |k, _| k == :body } })
warn "parsed #{issues.size} issues"
