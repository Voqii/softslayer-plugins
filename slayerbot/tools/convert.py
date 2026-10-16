#!/usr/bin/env python3
"""Turn an ancHelperBot config's `questions:` list into SlayerBot `topics:`.

Usage: python3 -I convert.py ancHelperBot-config.yml > topics.yml

Questions with the same answer become one topic. Exact duplicate questions are dropped, and the
stray " -" some lines have before ANSWER is removed. A topic whose lines are all commented out
("#- question ANSWER ...") comes out as `enabled: false`. Topic ids come from IDS below (matched
against the start of the answer), or else from the first question.
"""
import json
import re
import sys

# answer prefix -> topic id. Order doesn't matter, prefixes are unique.
IDS = {
    "probably not": "fuse",
    "You know the rules": "rickroll-1",
    "you wouldn't get this": "rickroll-2",
    "never gonna let you down": "rickroll-3",
    "never gonna make you cry": "rickroll-4",
    "never gonna tell a lie": "rickroll-5",
    "There is &cno land claim": "land-claims",
    "Our core gameplay": "what-to-do",
    "Do [/discord]": "discord",
    "You can make money": "money",
    "Setting more homes": "homes",
    "SS+ is our vip rank": "donate",
    "For the MCMMo plugin": "mcmmo-skills",
    "To lock your chests": "chest-lock",
    "You have to be a trustworthy": "staff",
    "KeepInventory is on": "keepinventory",
    "To leave Death Valley": "leave-death-valley",
    "The server has variable render": "render-distance",
    "There is a public exp": "xp-farm",
    "Right-click the sign": "buy",
    "Use the command &a/pay": "pay",
    "The world border": "world-border",
    "To get a shop plot": "shop-plot",
    "SoftSlayer is currently": "server-age",
    "Left-click the sign in shops": "sell-old",
    "You can verify your clan": "clan-verify",
    "To rank up": "rank-up",
    "Check your money": "balance",
    "You can explore the": "what-to-do-2",
    "The map will never reset": "map-reset",
    "A live map": "live-map",
    "To team": "team",
    "Stealing is against": "stealing",
    "Griefing is against": "griefing",
    "Type [i] in chat": "show-item",
    "We have a Consent PvP": "pvp",
    "To use 18+ chat": "adult-chat",
    "You can only sell diamonds": "sell",
    "Tnt duping": "tnt-duping",
    "Death Valley is an arena": "death-valley",
    "The AFK Park": "afk-park",
    "To get to the end": "the-end",
    "To vote": "vote",
    "Chunk loaders": "chunk-loaders",
    "Auto-clickers": "auto-clickers",
    "To move, you first": "cant-move",
    "Dupers are not": "dupers",
    "Yes, fullbright": "fullbright",
    "The difficulty": "difficulty",
    "The admins are": "admins",
    "The emods are": "emods",
    "yo yo": "yo-yo",
}

# Topics whose questions get folded into another topic (old answer is dropped).
# "how do i sell stuff" was in both of these with different answers.
MERGE = {"sell-old": "sell"}

# Extra phrasings that weren't in the ancHelperBot config. Only questions the existing answer
# really answers; nothing here changes an answer. Many are how younger (8-14) players ask:
# "can i be X", describing the problem ("i cant move"), and game words ("ender dragon").
EXTRA = {
    "what-to-do": ["what is this server about", "what kind of server is this"],
    "what-to-do-2": ["what do i do", "what should i do", "what do i do now", "im new what do i do", "what do i do first"],
    "discord": ["discord", "can i have the discord", "discord link", "what is the discord link",
                "can you send the discord link"],
    "money": ["whats the best way to make money", "how can i earn money", "how do i get money fast",
              "where do i get money", "how do i get rich"],
    "homes": ["can i have more than one home", "how many homes can i have", "why cant i set another home"],
    "donate": ["how much is ss+", "how much is vip", "how do i buy ss+", "how do i buy vip",
               "can i buy a rank", "how do i buy a rank"],
    "land-claims": ["how do i claim my base", "can i claim my base", "how do i claim my house",
                    "how do i protect my base", "how do i protect my house", "can people break my house",
                    "how do i stop people from griefing my base"],
    "mcmmo-skills": ["what is mcmmo", "what are skills", "how do i level up my skills"],
    "chest-lock": ["how do i protect my chest", "how do i stop people from opening my chest",
                   "can people open my chest"],
    "staff": ["how do i become staff", "how do i become a mod", "are you hiring staff", "can i be staff",
              "can i be a mod", "can i be admin", "how do i become admin"],
    "keepinventory": ["do we keep our items when we die", "do you lose your items when you die",
                      "do i lose my stuff when i die", "will i lose my items if i die"],
    "leave-death-valley": ["how do i get out of death valley", "how do i get out of dv",
                           "how do i get back from death valley", "im stuck in death valley"],
    "render-distance": ["why is the render distance so low"],
    "buy": ["how do i buy from the shop", "how do i buy from shops"],
    "pay": ["how do i pay someone", "how do i send someone money", "how do i give money to my friend",
            "how do i give someone money"],
    "world-border": ["how big is the world", "is there a world border", "how big is the border"],
    "shop-plot": ["how do i make my own shop", "can i have a shop", "how do i open a shop"],
    "server-age": ["when was the server made", "how long has the server been around"],
    "sell": ["where do i sell my stuff", "how to sell"],
    "rank-up": ["how do i get a higher rank", "how do i get a rank"],
    "balance": ["how do i check my balance", "how do i see my money", "how much money do i have",
                "how do i check my money"],
    "map-reset": ["will the map reset", "will the world reset", "does the world get reset"],
    "live-map": ["is there a map of the world", "where can i see the map"],
    "team": ["how do i join a clan", "can someone team with me"],
    "griefing": ["can i break other peoples houses", "can i destroy peoples houses", "can i blow up houses"],
    "stealing": ["can i take other peoples stuff", "can i rob people"],
    "pvp": ["can i pvp", "how do i turn on pvp", "how do i turn off pvp", "can people kill me",
            "why cant i hit people", "why cant i attack players"],
    "death-valley": ["what is death valley for"],
    "the-end": ["where is the ender dragon", "how do i get to the ender dragon", "how do i find the end portal"],
    "vote": ["where can i vote", "how do i vote for the server"],
    "cant-move": ["i cant move", "i cant walk", "why cant i walk", "why am i frozen"],
    "difficulty": ["is it hard mode", "what difficulty is it"],
}

# Call-and-response jokes: answer only when the whole message is exactly one of these (exact: true).
JOKES = {
    "pfm": ("On top!", ["PFM where?", "where is PFM", "wheres PFM"]),
    "ibmoc": ("MAS", ["IBMOC"]),
    "great-power": ("comes great responsibility", ["with great power"]),
    "fus-ro": ("DAAAHHHH", ["FUS RO"]),
    "cavesmith": ("I'm Slayerbot", ["I'm Cavesmith"]),
}


def topic_id(answer, first_question):
    """(id to file the question under, whether this line's answer is that topic's own answer)."""
    for prefix, tid in IDS.items():
        if answer.startswith(prefix):
            return MERGE.get(tid, tid), tid not in MERGE
    return re.sub(r"[^a-z0-9]+", "-", first_question.lower()).strip("-"), True


def main(path):
    topics = {}  # id -> {"answer": str, "questions": [str]}
    for line in open(path, encoding="utf-8"):
        m = re.match(r"\s*(#\s*)?-\s+(.*?)\s+ANSWER\s+(.*?)\s*$", line)
        if not m:
            continue
        commented = m.group(1) is not None
        question = re.sub(r"\s+-$", "", m.group(2)).strip()
        answer = m.group(3)
        tid, own = topic_id(answer, question)
        t = topics.setdefault(tid, {"answer": None, "questions": [], "enabled": False})
        t["enabled"] |= not commented
        if own:
            t["answer"] = answer
        if question.lower() not in (q.lower() for q in t["questions"]):
            t["questions"].append(question)

    for tid, extra in EXTRA.items():
        topics[tid]["questions"] += [q for q in extra if q.lower() not in (x.lower() for x in topics[tid]["questions"])]

    for tid, (answer, questions) in JOKES.items():
        topics[tid] = {"answer": answer, "questions": questions, "enabled": True, "exact": True}

    print("topics:")
    for tid, t in topics.items():
        print(f"  {tid}:")
        if not t["enabled"]:
            print("    enabled: false")
        if t.get("exact"):
            print("    exact: true")
        print(f"    answer: {json.dumps(t['answer'], ensure_ascii=False)}")
        print("    questions:")
        for q in t["questions"]:
            print(f"      - {json.dumps(q, ensure_ascii=False)}")


if __name__ == "__main__":
    main(sys.argv[1])
