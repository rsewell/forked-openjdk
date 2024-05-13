/*
 * Copyright (c) 2024, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 *
 */

#ifndef SHARE_NMT_NMTTREAP_HPP
#define SHARE_NMT_NMTTREAP_HPP

#include "memory/allocation.hpp"
#include "runtime/os.hpp"
#include "utilities/debug.hpp"
#include "utilities/globalDefinitions.hpp"
#include "utilities/growableArray.hpp"
#include "utilities/macros.hpp"
#include <stdint.h>

// A Treap is a self-balanced binary tree where each node is equipped with a
// priority. It adds the invariant that the priority of a parent P is strictly larger
// larger than the priority of its children. When priorities are randomly
// assigned the tree is balanced.
// All operations are defined through merge and split, which are each other's inverse.
// merge(left_treap, right_treap) => treap where left_treap <= right_treap
// split(treap, key) => (left_treap, right_treap)  where left_treap <= right_treap
// Recursion is used in these, but the depth of the call stack is the depth of
// the tree which is O(log n) so we are safe from stack overflow.
// TreapNode has LEQ nodes on the left, GT nodes on the right.
//
// COMPARATOR must have a static function `cmp(a,b)` which returns:
//     - an int < 0 when a < b
//     - an int == 0 when a == b
//     - an int > 0 when a > b
// ALLOCATOR must check for oom and exit, as Treap currently does not handle the allocation
// failing.

template<typename K, typename V, typename COMPARATOR, typename ALLOCATOR>
class Treap {
  friend class VMATreeTest;
  friend class TreapTest;
public:
  class TreapNode {
    friend Treap;
    uint64_t _priority;
    const K _key;
    V _value;

    TreapNode* _left;
    TreapNode* _right;

  public:
    TreapNode(const K& k, const V& v, uint64_t p)
      : _priority(p),
        _key(k),
        _value(v),
        _left(nullptr),
        _right(nullptr) {
    }

    const K& key() const { return _key; }
    V& val() { return _value; }

    TreapNode* left() const { return _left; }
    TreapNode* right() const { return _right; }
  };

private:
  ALLOCATOR _allocator;
  TreapNode* _root;
  uint64_t _prng_seed;
  int _node_count;

  uint64_t prng_next() {
    // Taken directly off of JFRPrng
    static const constexpr uint64_t PrngMult = 0x5DEECE66DLL;
    static const constexpr uint64_t PrngAdd = 0xB;
    static const constexpr uint64_t PrngModPower = 48;
    static const constexpr uint64_t PrngModMask = (static_cast<uint64_t>(1) << PrngModPower) - 1;
    _prng_seed = (PrngMult * _prng_seed + PrngAdd) & PrngModMask;
    return _prng_seed;
  }

  struct node_pair {
    TreapNode* left;
    TreapNode* right;
  };

  enum SplitMode {
    LT, // <
    LEQ // <=
  };

  // Split tree at head into two trees, SplitMode decides where EQ values go.
  // We have SplitMode because it makes remove() trivial to implement.
  static node_pair split(TreapNode* head, const K& key, SplitMode mode = LEQ DEBUG_ONLY(COMMA int recur_count = 0)) {
    assert(recur_count < 200, "Call-stack depth should never exceed 200");

    if (head == nullptr) {
      return {nullptr, nullptr};
    }
    if ((COMPARATOR::cmp(head->_key, key) <= 0 && mode == LEQ) || (COMPARATOR::cmp(head->_key, key) < 0 && mode == LT)) {
      node_pair p = split(head->_right, key, mode DEBUG_ONLY(COMMA recur_count + 1));
      head->_right = p.left;
      return node_pair{head, p.right};
    } else {
      node_pair p = split(head->_left, key, mode DEBUG_ONLY(COMMA recur_count + 1));
      head->_left = p.right;
      return node_pair{p.left, head};
    }
  }

  // Invariant: left is a treap whose keys are LEQ to the keys in right.
  static TreapNode* merge(TreapNode* left, TreapNode* right DEBUG_ONLY(COMMA int recur_count = 0)) {
    assert(recur_count < 200, "Call-stack depth should never exceed 200");

    if (left == nullptr) return right;
    if (right == nullptr) return left;

    if (left->_priority > right->_priority) {
      // We need
      //      LEFT
      //         |
      //         RIGHT
      // for the invariant re: priorities to hold.
      left->_right = merge(left->_right, right DEBUG_ONLY(COMMA recur_count + 1));
      return left;
    } else {
      // We need
      //         RIGHT
      //         |
      //      LEFT
      // for the invariant re: priorities to hold.
      right->_left = merge(left, right->_left DEBUG_ONLY(COMMA recur_count + 1));
      return right;
    }
  }

  static TreapNode* find(TreapNode* node, const K& k DEBUG_ONLY(COMMA int recur_count = 0)) {
    if (node == nullptr) {
      return nullptr;
    }

    int key_cmp_k = COMPARATOR::cmp(node->key(), k);

    if (key_cmp_k == 0) { // key EQ k
      return node;
    }

    if (key_cmp_k < 0) { // key LT k
      return find(node->right(), k DEBUG_ONLY(COMMA recur_count + 1));
    } else { // key GT k
      return find(node->left(), k DEBUG_ONLY(COMMA recur_count + 1));
    }
  }

#ifdef ASSERT
  void verify_self() {
    const double expected_maximum_depth = log(this->_node_count+1) * 5;
    // Find the maximum depth through DFS and ensure that the priority invariant holds.
    int maximum_depth_found = 0;

    struct DFS {
      int depth;
      uint64_t parent_prio;
      TreapNode* n;
    };
    GrowableArrayCHeap<DFS, mtNMT> to_visit;
    constexpr const uint64_t positive_infinity = 0xFFFFFFFFFFFFFFFF;

    to_visit.push({0, positive_infinity, this->_root});
    while (!to_visit.is_empty()) {
      DFS head = to_visit.pop();
      if (head.n == nullptr) continue;
      maximum_depth_found = MAX2(maximum_depth_found, head.depth);

      assert(head.parent_prio >= head.n->_priority, "broken priority invariant");

      to_visit.push({head.depth + 1, head.n->_priority, head.n->left()});
      to_visit.push({head.depth + 1, head.n->_priority, head.n->right()});
    }
    assert(maximum_depth_found <= (int)expected_maximum_depth, "depth unexpectedly large");

    // Visit everything in order, see that the key ordering is monotonically increasing.
    TreapNode* last_seen = nullptr;
    bool failed = false;
    int seen_count = 0;
    this->visit_in_order([&](TreapNode* node) {
      seen_count++;
      if (last_seen == nullptr) {
        last_seen = node;
        return;
      }
      if (COMPARATOR::cmp(last_seen->key(), node->key()) > 0) {
        failed = false;
      }
      last_seen = node;
    });
    assert(seen_count == _node_count, "the number of visited nodes do not match with the number of stored nodes");
    assert(!failed, "keys was not monotonically strongly increasing when visiting in order");
  }
#endif // ASSERT

public:
  Treap(uint64_t seed = static_cast<uint64_t>(os::random()))
  : _allocator(),
    _root(nullptr),
    _prng_seed(seed),
    _node_count(0) {}

  ~Treap() {
    this->remove_all();
  }

  void upsert(const K& k, const V& v) {
    verify_self();

    TreapNode* found = find(_root, k);
    if (found != nullptr) {
      // Already exists, update value.
      found->_value = v;
      return;
    }
    _node_count++;
    // Doesn't exist, make node
    void* node_place = _allocator.allocate(sizeof(TreapNode));
    uint64_t prio = prng_next();
    TreapNode* node = new (node_place) TreapNode(k, v, prio);

    // (LEQ_k, GT_k)
    node_pair split_up = split(this->_root, k);
    // merge(merge(LEQ_k, EQ_k), GT_k)
    this->_root = merge(merge(split_up.left, node), split_up.right);
  }

  void remove(const K& k) {
    verify_self();

    // (LEQ_k, GT_k)
    node_pair first_split = split(this->_root, k, LEQ);
    // (LT_k, GEQ_k) == (LT_k, EQ_k) since it's from LEQ_k and keys are unique.
    node_pair second_split = split(first_split.left, k, LT);

    if (second_split.right != nullptr) {
      // The key k existed, we delete it.
      _node_count--;
      _allocator.free(second_split.right);
    }
    // Merge together everything
    _root = merge(second_split.left, first_split.right);
  }

  // Delete all nodes.
  void remove_all() {
    _node_count = 0;
    GrowableArrayCHeap<TreapNode*, mtNMT> to_delete;
    to_delete.push(_root);

    while (!to_delete.is_empty()) {
      TreapNode* head = to_delete.pop();
      if (head == nullptr) continue;
      to_delete.push(head->_left);
      to_delete.push(head->_right);
      _allocator.free(head);
    }
  }

  TreapNode* closest_leq(const K& key) {
    TreapNode* candidate = nullptr;
    TreapNode* pos = _root;
    while (pos != nullptr) {
      int cmp_r = COMPARATOR::cmp(pos->key(), key);
      if (cmp_r == 0) { // Exact match
        candidate = pos;
        break; // Can't become better than that.
      }
      if (cmp_r < 0) {
        // Found a match, try to find a better one.
        candidate = pos;
        pos = pos->_right;
      } else if (cmp_r > 0) {
        pos = pos->_left;
      }
    }
    return candidate;
  }

  // Visit all TreapNodes in ascending key order.
  template<typename F>
  void visit_in_order(F f) const {
    GrowableArrayCHeap<TreapNode*, mtNMT> to_visit;
    TreapNode* head = _root;
    while (!to_visit.is_empty() || head != nullptr) {
      while (head != nullptr) {
        to_visit.push(head);
        head = head->left();
      }
      head = to_visit.pop();
      f(head);
      head = head->right();
    }
  }

  // Visit all TreapNodes in ascending order whose keys are in range [from, to).
  template<typename F>
  void visit_range_in_order(const K& from, const K& to, F f) {
    GrowableArrayCHeap<TreapNode*, mtNMT> to_visit;
    TreapNode* head = _root;
    while (!to_visit.is_empty() || head != nullptr) {
      while (head != nullptr) {
        int cmp_from = COMPARATOR::cmp(head->key(), from);
        to_visit.push(head);
        if (cmp_from >= 0) {
          head = head->left();
        } else {
          // We've reached a node which is strictly less than from
          // We don't need to visit any further to the left.
          break;
        }
      }
      head = to_visit.pop();
      const int cmp_from = COMPARATOR::cmp(head->key(), from);
      const int cmp_to = COMPARATOR::cmp(head->key(), to);
      if (cmp_from >= 0 && cmp_to < 0) {
        f(head);
      }
      if (cmp_to < 0) {
        head = head->right();
      } else {
        head = nullptr;
      }
    }
  }
};

class TreapCHeapAllocator {
public:
  void* allocate(size_t sz) {
    void* allocation = os::malloc(sz, mtNMT);
    if (allocation == nullptr) {
      vm_exit_out_of_memory(sz, OOM_MALLOC_ERROR, "treap failed allocation");
    }
    return allocation;
  }

  void free(void* ptr) {
    os::free(ptr);
  }
};

template<typename K, typename V, typename COMPARATOR>
using TreapCHeap = Treap<K, V, COMPARATOR, TreapCHeapAllocator>;

#endif //SHARE_NMT_NMTTREAP_HPP
