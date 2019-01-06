package com.kumuluz.ee.samples.microservices.simple;

import com.kumuluz.ee.discovery.annotations.DiscoverService;
import com.kumuluz.ee.logs.LogManager;
import com.kumuluz.ee.logs.Logger;
import com.kumuluz.ee.logs.cdi.Log;
import com.kumuluz.ee.samples.microservices.simple.Models.Cart;
import com.kumuluz.ee.samples.microservices.simple.Models.Item;
import org.json.JSONArray;
import org.json.JSONObject;

import javax.enterprise.context.RequestScoped;
import javax.inject.Inject;
import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;
import javax.persistence.TypedQuery;
import javax.ws.rs.*;
import javax.ws.rs.client.Entity;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.util.List;
import java.util.Optional;

@Path("/cart")
@RequestScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Log
public class CartResource {

    @PersistenceContext
    private EntityManager em;

    @Inject
    @DiscoverService(value = "order-service", environment = "dev", version = "*")
    private Optional<WebTarget> orderService;

    @Inject
    @DiscoverService(value = "catalog-service", environment = "dev", version = "*")
    private Optional<WebTarget> catalogService;

    private static final Logger LOG = LogManager.getLogger(CartResource.class.getName());

    @GET
    public Response getCarts() {
        TypedQuery<Cart> query = em.createNamedQuery("Cart.findAll", Cart.class);

        List<Cart> carts = query.getResultList();

        return Response.ok(carts).build();
    }

    @GET
    @Path("/{id}")
    public Response getCart(@PathParam("id") Integer id) {
        Cart c = em.find(Cart.class, id);

        if (c == null)
            return Response.status(Response.Status.NOT_FOUND).build();

        return Response.ok(c).build();
    }

    @POST
    public Response createNewCart() {
        Cart c = new Cart();
        c.setId(null);
        c.setCartJSON("{\"items\": []}");

        em.getTransaction().begin();

        em.persist(c);

        em.getTransaction().commit();

        LOG.trace("New cart created.");

        return Response.status(Response.Status.CREATED).entity(c.getId()).build();
    }

    @POST
    @Path("/addToCart/{cartId}")
    public Response addItemToCart(Item i, @PathParam("cartId") Integer id) {
        if (catalogService.isPresent()) {
            WebTarget service = catalogService.get().path("products/getProductQty/productId/"+i.getProductId());
            Response response;
            try {
                response = service.request().get();

                JSONObject qtyJSON = new JSONObject(response.readEntity(String.class));
                int qty = qtyJSON.getInt("qty");

                if (qty < i.getQty()) {
                    JSONObject error = new JSONObject();
                    error.put("error", "Product is out of stock");
                    LOG.trace("Product " + i.getProductId() + "is out of stock");
                    return Response.status(Response.Status.METHOD_NOT_ALLOWED).entity(error.toString()).build();
                }
            } catch (ProcessingException e) {
                e.printStackTrace();
                return Response.status(408).build();
            } catch (Exception e) {
                e.printStackTrace();
                return Response.status(500).build();
            }
        }

        em.getTransaction().begin();

        Cart c = em.find(Cart.class, id);

        String cartJSONString = c.getCartJSON();
        JSONObject cartJSON = new JSONObject(cartJSONString);

        JSONArray items = (JSONArray) cartJSON.get("items");
        items.put(i.getItemJSON());

        cartJSON.put("items", items);
        c.setCartJSON(cartJSON.toString());

        em.persist(c);
        em.getTransaction().commit();

        return Response.status(Response.Status.OK).entity(c).build();
    }

    @POST
    @Path("/removeFromCart/{cartId}")
    public Response removeItemFromCart(Item i, @PathParam("cartId") Integer id) throws Exception {
        em.getTransaction().begin();

        Cart c = em.find(Cart.class, id);

        String cartJSONString = c.getCartJSON();
        JSONObject cartJSON = new JSONObject(cartJSONString);

        JSONArray items = (JSONArray) cartJSON.get("items");

        int index = 0;
        for (Object item : items) {
            JSONObject itemJSON = (JSONObject) item;

            if (Integer.parseInt(itemJSON.get("productId").toString()) == i.getProductId()) {
                break;
            }
            index++;
        }

        items.remove(index);

        cartJSON.put("items", items);
        c.setCartJSON(cartJSON.toString());

        em.persist(c);
        em.getTransaction().commit();

        return Response.status(Response.Status.OK).entity(c).build();
    }

    @POST
    @Path("/completeOrder/{cartId}")
    public Response completeOrder(@PathParam("cartId") Integer cartId) throws Exception {
        Cart c = em.find(Cart.class, cartId);

        if (orderService.isPresent()) {
            WebTarget service = orderService.get().path("orders/completeOrder");

            Response response;
            try {
                JSONObject cartJSON = new JSONObject(c.getCartJSON());
                response = service.request().post(Entity.json(cartJSON.toString()));
            } catch (ProcessingException e) {
                e.printStackTrace();
                return Response.status(408).build();
            } catch (Exception e) {
                e.printStackTrace();
                return Response.status(500).build();
            }

            return Response.fromResponse(response).build();
        } else {
            return Response.status(Response.Status.NO_CONTENT).build();
        }
    }

}
