/**
 * Copyright (c) 2011-2015, SpaceToad and the BuildCraft Team
 * http://www.mod-buildcraft.com
 *
 * BuildCraft is distributed under the terms of the Minecraft Mod Public
 * License 1.0, or MMPL. Please check the contents of the license located in
 * http://www.mod-buildcraft.com/MMPL-1.0.txt
 */
package buildcraft;

import java.util.EnumMap;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedDeque;

import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.NetHandlerPlayServer;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.play.server.S3FPacketCustomPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import cpw.mods.fml.common.network.FMLEmbeddedChannel;
import cpw.mods.fml.common.network.FMLOutboundHandler;
import cpw.mods.fml.common.network.FMLOutboundHandler.OutboundTarget;
import cpw.mods.fml.relauncher.Side;
import buildcraft.core.DefaultProps;
import buildcraft.core.lib.network.Packet;
import net.minecraft.world.WorldServer;

public class BuildCraftMod {
	private static PacketSender sender = new PacketSender();
	private static Thread senderThread = new Thread(sender);

	public EnumMap<Side, FMLEmbeddedChannel> channels;

	abstract static class SendRequest {
		final Packet packet;
		final BuildCraftMod source;

		SendRequest(BuildCraftMod source, Packet packet) {
			this.packet = packet;
			this.source = source;
		}

		abstract boolean isValid(EntityPlayer player);
	}

	class PlayerSendRequest extends SendRequest {
		EntityPlayer player;

		PlayerSendRequest(BuildCraftMod source, Packet packet, EntityPlayer player) {
			super(source, packet);
			this.player = player;
		}

		boolean isValid(EntityPlayer player) {
			return this.player.equals(player);
		}
	}

	class EntitySendRequest extends SendRequest {
		Entity entity;

		EntitySendRequest(BuildCraftMod source, Packet packet, Entity entity) {
			super(source, packet);
			this.entity = entity;
		}

		boolean isValid(EntityPlayer player) {
			if (player.worldObj.equals(entity.worldObj)) {
				if (player.worldObj instanceof WorldServer) {
					return ((WorldServer) player.worldObj).getEntityTracker().getTrackingPlayers(entity).contains(player);
				} else {
					return true;
				}
			} else {
				return false;
			}
		}
	}

	class WorldSendRequest extends SendRequest {
		final int dimensionId;

		WorldSendRequest(BuildCraftMod source, Packet packet, int dimensionId) {
			super(source, packet);
			this.dimensionId = dimensionId;
		}

		boolean isValid(EntityPlayer player) {
			return player.worldObj.provider.dimensionId == dimensionId;
		}
	}

	class LocationSendRequest extends SendRequest {
		final int dimensionId;
		final int x, y, z, md;

		LocationSendRequest(BuildCraftMod source, Packet packet, int dimensionId, int x, int y, int z, int md) {
			super(source, packet);
			this.dimensionId = dimensionId;
			this.x = x;
			this.y = y;
			this.z = z;
			this.md = md * md;
		}

		boolean isValid(EntityPlayer player) {
			return dimensionId == player.worldObj.provider.dimensionId
					&& player.getDistanceSq(x, y, z) <= md;
		}
	}

	static class PacketSender implements Runnable {
		private Queue<SendRequest> packets = new ConcurrentLinkedDeque<SendRequest>();

		@Override
		public void run() {
			while (true) {
				try {
					Thread.sleep(10);
				} catch (Exception e) {

				}

				while (!packets.isEmpty()) {
					SendRequest r = packets.remove();
					S3FPacketCustomPayload packetCustomPayload = new S3FPacketCustomPayload();
					net.minecraft.network.Packet p = r.source.channels.get(Side.SERVER).generatePacketFrom(r.packet);
					for (EntityPlayerMP player : (List<EntityPlayerMP>) MinecraftServer.getServer().getConfigurationManager().playerEntityList) {
						if (r.isValid(player)) {
							NetHandlerPlayServer handler = player.playerNetServerHandler;
							if (handler == null) {
								continue;
							}

							NetworkManager manager = handler.netManager;
							if (manager == null || !manager.isChannelOpen()) {
								continue;
							}

							manager.scheduleOutboundPacket(p);
						}
					}
				}
			}
		}

		public boolean add(SendRequest r) {
			return packets.offer(r);
		}
	}

	static {
		senderThread.start();
	}

	public void sendToPlayers(Packet packet, World world, int x, int y, int z, int maxDistance) {
		sender.add(new LocationSendRequest(this, packet, world.provider.dimensionId, x, y, z, maxDistance));
	}

	public void sendToPlayersNear(Packet packet, TileEntity tileEntity, int maxDistance) {
		sender.add(new LocationSendRequest(this, packet, tileEntity.getWorldObj().provider.dimensionId, tileEntity.xCoord, tileEntity.yCoord, tileEntity.zCoord, maxDistance));
	}

	public void sendToPlayersNear(Packet packet, TileEntity tileEntity) {
		sendToPlayersNear(packet, tileEntity, DefaultProps.NETWORK_UPDATE_RANGE);
	}

	public void sendToWorld(Packet packet, World world) {
		sender.add(new WorldSendRequest(this, packet, world.provider.dimensionId));
	}

	public void sendToEntity(Packet packet, Entity entity) {
		sender.add(new EntitySendRequest(this, packet, entity));
	}
	
	public void sendToPlayer(EntityPlayer entityplayer, Packet packet) {
		sender.add(new PlayerSendRequest(this, packet, entityplayer));
	}

	/* public void sendToAll(Packet packet) {
		try {
			channels.get(Side.SERVER).attr(FMLOutboundHandler.FML_MESSAGETARGET)
					.set(FMLOutboundHandler.OutboundTarget.ALL);
			channels.get(Side.SERVER).writeOutbound(packet);
		} catch (Throwable t) {
			BCLog.logger.log(Level.WARN, "sendToAll crash", t);
		}
	} */

	public void sendToServer(Packet packet) {
		try {
			channels.get(Side.CLIENT).attr(FMLOutboundHandler.FML_MESSAGETARGET).set(OutboundTarget.TOSERVER);
			channels.get(Side.CLIENT).writeOutbound(packet);
		} catch (Exception e) {
			e.printStackTrace();
		}
	}
}