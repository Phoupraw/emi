package dev.emi.emi.registry;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.Supplier;

import com.google.common.collect.Lists;
import com.google.common.collect.Sets;

import dev.emi.emi.EmiPort;
import dev.emi.emi.api.stack.EmiIngredient;
import dev.emi.emi.api.stack.EmiStack;
import dev.emi.emi.data.EmiData;
import dev.emi.emi.data.IndexStackData;
import dev.emi.emi.runtime.EmiHidden;
import dev.emi.emi.runtime.EmiLog;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import net.minecraft.block.Block;
import net.minecraft.fluid.Fluid;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroup;
import net.minecraft.item.ItemStack;
import net.minecraft.tag.TagKey;
import net.minecraft.util.Identifier;
import net.minecraft.util.collection.DefaultedList;

public class EmiStackList {
	private static final TagKey<Item> ITEM_HIDDEN = TagKey.of(EmiPort.getItemRegistry().getKey(), new Identifier("c", "hidden_from_recipe_viewers"));
	private static final TagKey<Block> BLOCK_HIDDEN = TagKey.of(EmiPort.getBlockRegistry().getKey(), new Identifier("c", "hidden_from_recipe_viewers"));
	private static final TagKey<Fluid> FLUID_HIDDEN = TagKey.of(EmiPort.getFluidRegistry().getKey(), new Identifier("c", "hidden_from_recipe_viewers"));
	public static List<Predicate<EmiStack>> invalidators = Lists.newArrayList();
	public static List<EmiStack> stacks = List.of();
	public static List<EmiStack> filteredStacks = List.of();
	public static Object2IntMap<EmiStack> indices = new Object2IntOpenHashMap<>();

	public static void clear() {
		invalidators.clear();
		stacks = List.of();
		indices.clear();
	}

	public static void reload() {
		List<IndexGroup> groups = Lists.newArrayList();
		Map<String, IndexGroup> namespaceGroups = new LinkedHashMap<>();
		for (Item item : EmiPort.getItemRegistry()) {
			DefaultedList<ItemStack> itemStacks = DefaultedList.of();
			item.appendStacks(ItemGroup.SEARCH, itemStacks);
			List<EmiStack> stacks = itemStacks.stream().filter(s -> !s.isEmpty()).map(EmiStack::of).toList();
			if (!stacks.isEmpty()) {
				namespaceGroups.computeIfAbsent(stacks.get(0).getId().getNamespace(), (k) -> new IndexGroup()).stacks.addAll(stacks);
			}
		}
		groups.addAll(namespaceGroups.values());
		IndexGroup fluidGroup = new IndexGroup();
		for (Fluid fluid : EmiPort.getFluidRegistry()) {
			if (fluid.isStill(fluid.getDefaultState())) {
				EmiStack fs = EmiStack.of(fluid);
				fluidGroup.stacks.add(fs);
			}
		}
		groups.add(fluidGroup);
		
		stacks = Lists.newLinkedList();
		for (IndexGroup group : groups) {
			if (group.shouldDisplay()) {
				stacks.addAll(group.stacks);
			}
		}
	}

	@SuppressWarnings("deprecation")
	public static void bake() {
		stacks.removeIf(s -> {
			for (Predicate<EmiStack> invalidator : invalidators) {
				if (invalidator.test(s)) {
					return true;
				}
			}
			if (s.getKey() instanceof Item i) {
				if (i instanceof BlockItem bi && bi.getBlock().getDefaultState().isIn(BLOCK_HIDDEN)) {
					return true;
				} else if (s.getItemStack().isIn(ITEM_HIDDEN)) {
					return true;
				}
			} else if (s.getKey() instanceof Fluid f) {
				if (f.isIn(FLUID_HIDDEN)) {
					return true;
				}
			}
			return false;
		});
		for (Supplier<IndexStackData> supplier : EmiData.stackData) {
			IndexStackData ssd = supplier.get();
			if (!ssd.removed().isEmpty()) {
				stacks.removeIf(s -> {
					for (EmiIngredient invalidator : ssd.removed()) {
						for (EmiStack stack : invalidator.getEmiStacks()) {
							if (stack.equals(s)) {
								return true;
							}
						}
					}
					return false;
				});
			}
			for (IndexStackData.Added added : ssd.added()) {
				if (added.added().isEmpty()) {
					continue;
				}
				if (added.after().isEmpty()) {
					stacks.add(added.added().getEmiStacks().get(0));
				} else {
					int i = stacks.indexOf(added.after());
					if (i == -1) {
						i = stacks.size() - 1;
					}
					stacks.add(i + 1, added.added().getEmiStacks().get(0));
				}
			}
		}
		stacks = stacks.stream().filter(stack -> {
			String name = "Unknown";
			String id = "unknown";
			try {
				if (stack.isEmpty()) {
					return false;
				}
				name = stack.toString();
				id = stack.getId().toString();
				if (name != null && stack.getKey() != null && stack.getName() != null && stack.getTooltip() != null) {
					return true;
				}
				EmiLog.warn("Hiding stack " + name + " with id " + id + " from index due to returning dangerous values");
				return false;
			} catch (Throwable t) {
				EmiLog.warn("Hiding stack " + name + " with id " + id + " from index due to throwing errors");
				t.printStackTrace();
				return false;
			}
		}).toList();
		for (int i = 0; i < stacks.size(); i++) {
			indices.put(stacks.get(i), i);
		}
		bakeFiltered();
	}

	public static void bakeFiltered() {
		filteredStacks = stacks.stream().filter(s -> !EmiHidden.isHidden(s)).toList();
	}

	public static class IndexGroup {
		public List<EmiStack> stacks = Lists.newArrayList();
		public Set<IndexGroup> suppressedBy = Sets.newHashSet();

		public boolean shouldDisplay() {
			for (IndexGroup suppressor : suppressedBy) {
				if (suppressor.shouldDisplay()) {
					return false;
				}
			}
			return true;
		}
	}
}